package dev.leo.rednotetrans

import android.content.Context
import android.util.LruCache
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import com.google.mlkit.nl.translate.Translator as MlTranslator

/**
 * On-device translation via ML Kit - Google's Translate models running locally.
 *
 * The public translate_a/single endpoint (what Chrome's page translator calls) is bot-blocked:
 * it answers HTTP 200 with a "Sorry..." HTML page, so it fails silently and cannot be relied on.
 * On-device also removes per-string network latency, which matters a lot when labels have to
 * land while the user is still scrolling, and it works with no connection at all.
 *
 * Source is fixed to Chinese: callers only pass strings that [hasHan] accepted.
 */
object Translator {

    enum class Model { IDLE, DOWNLOADING, READY, FAILED }

    private val cache = LruCache<String, String>(4000)

    private var client: MlTranslator? = null
    private var clientLang: String? = null

    @Volatile
    var model: Model = Model.IDLE
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** Why the cloud engine could not answer, in words the settings screen can show.
     *  Non-null is exactly the "it failed" signal - no second flag to keep in step. */
    @Volatile
    var cloudError: String? = null
        private set

    /**
     * A failing cloud engine is retried after this, rather than never again. Caching the
     * fallback under the cloud key was what made one bad call look like a permanent
     * downgrade to on-device.
     */
    private const val RETRY_AFTER_MS = 60_000L

    @Volatile
    private var cloudBlockedUntil = 0L

    /** Cloud endpoints bot-block under a burst; a feed screen does not need more than this. */
    private val gate = Semaphore(4)

    /**
     * Strings a cloud call is already paying for. Without this, a scroll that starts a
     * second scan while the first request is still open buys the same translations twice.
     */
    private val inFlight = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * One cloud call at a time. Scrolling starts a scan every few hundred ms, and letting
     * each fire its own request piled six of them onto the provider at once - they timed
     * each other out, and a timed-out request is billed exactly like a successful one.
     */
    private val cloudTurn = Mutex()

    // The engine is part of the key: without it, switching back ends would keep serving
    // whatever the previous one produced and the change would look like it did nothing.
    private fun key(text: String, target: String, engine: String) = "$engine $target $text"

    /**
     * A cloud translation if there is one, otherwise whatever on-device produced for the
     * same string. The fallback is stored under its own engine, so selecting the cloud again
     * later does not serve stale on-device text as though the cloud had answered.
     */
    fun cached(text: String, target: String, engine: String): String? =
        cache.get(key(text, target, engine))
            ?: cache.get(key(text, target, Prefs.ENGINE_ON_DEVICE))

    /**
     * Strictly this engine, with no fallback. Deciding what still needs translating has to
     * use this: asking the forgiving [cached] would see the on-device fallback, call the
     * string done, and never send it to the cloud engine at all.
     */
    fun cachedExact(text: String, target: String, engine: String): String? =
        cache.get(key(text, target, engine))

    @Synchronized
    private fun clientFor(target: String): MlTranslator {
        if (client == null || clientLang != target) {
            client?.close()
            client = Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.CHINESE)
                    .setTargetLanguage(
                        TranslateLanguage.fromLanguageTag(target) ?: TranslateLanguage.ENGLISH
                    )
                    .build()
            )
            clientLang = target
            model = Model.IDLE
        }
        return client!!
    }

    /** Downloads the language pair if it is not already on the device. Safe to call repeatedly. */
    suspend fun ensureModel(target: String): Boolean {
        val c = clientFor(target)
        if (model == Model.READY) return true
        model = Model.DOWNLOADING
        return runCatching {
            c.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        }.fold(
            onSuccess = { model = Model.READY; lastError = null; true },
            onFailure = { model = Model.FAILED; lastError = it.message ?: it.javaClass.simpleName; false },
        )
    }

    /**
     * On-device pass. Local and quick, so the overlay can draw something immediately rather
     * than waiting on a network round trip that a single scroll would cancel anyway.
     */
    suspend fun fillOnDevice(texts: List<String>, target: String) {
        val pending = texts.distinct()
            .filter { cache.get(key(it, target, Prefs.ENGINE_ON_DEVICE)) == null }
        viaOnDevice(pending, target, Prefs.ENGINE_ON_DEVICE)
    }

    /**
     * Cloud pass, run after the on-device one has already put labels on screen. Returns true
     * when anything new landed and the caller should redraw to upgrade what it drew.
     */
    suspend fun fillCloud(texts: List<String>, target: String, ctx: Context): Boolean {
        val engine = Prefs.of(ctx).engine
        if (engine == Prefs.ENGINE_ON_DEVICE) return false
        if (System.currentTimeMillis() < cloudBlockedUntil) return false

        if (texts.none { cache.get(key(it, target, engine)) == null }) return false

        return cloudTurn.withLock {
            // Re-checked now that it is this call's turn. While it waited, the batch ahead
            // of it will usually have translated the very same strings - a scroll shows the
            // same cards to several scans - so most queued calls end here without spending
            // anything at all.
            if (System.currentTimeMillis() < cloudBlockedUntil) return@withLock false
            val pending = texts.distinct()
                .filter { cache.get(key(it, target, engine)) == null && it !in inFlight }
            if (pending.isEmpty()) return@withLock false

            inFlight.addAll(pending)
            val done = try {
                when (engine) {
                    Prefs.ENGINE_GOOGLE -> viaGoogle(pending, target, engine)
                    else -> viaDeepSeek(pending, target, ctx, engine)
                }
            } finally {
                inFlight.removeAll(pending.toSet())
            }
            if (done.size < pending.size) {
                cloudError = when (engine) {
                    Prefs.ENGINE_GOOGLE -> GoogleWebEngine.lastError
                    else -> DeepSeekEngine.lastError ?: "No API key set"
                }
                cloudBlockedUntil = System.currentTimeMillis() + RETRY_AFTER_MS
            } else {
                cloudError = null
            }
            done.isNotEmpty()
        }
    }

    private suspend fun viaGoogle(
        texts: List<String>,
        target: String,
        engine: String,
    ): Set<String> =
        coroutineScope {
            texts.map { text ->
                async(Dispatchers.IO) {
                    gate.withPermit { GoogleWebEngine.translate(text, target) }
                        ?.also { cache.put(key(text, target, engine), it) }
                        ?.let { text }
                }
            }.awaitAll().filterNotNull().toSet()
        }

    private suspend fun viaDeepSeek(
        texts: List<String>,
        target: String,
        ctx: Context,
        engine: String,
    ): Set<String> {
        val apiKey = SecretStore.load(ctx)?.takeIf { it.isNotBlank() } ?: return emptySet()
        val done = HashSet<String>()
        for (chunk in texts.chunked(DeepSeekEngine.BATCH_MAX)) {
            // The cache write happens inside the IO block on purpose. If the caller is
            // cancelled mid-request - which a single scroll does - withContext throws on
            // resume and the reply would be dropped even though DeepSeek has already
            // charged for it, so the next scan would buy the very same answer again.
            val result = withContext(Dispatchers.IO) {
                DeepSeekEngine.translate(chunk, target, apiKey)?.also { reply ->
                    reply.forEach { (source, translated) ->
                        cache.put(key(source, target, engine), translated)
                    }
                }
            } ?: continue
            done += result.keys
        }
        return done
    }

    private suspend fun viaOnDevice(texts: List<String>, target: String, engine: String) {
        if (texts.isEmpty() || !ensureModel(target)) return
        coroutineScope {
            texts.map { text ->
                async {
                    // The on-device model is small and derails on RedNote's decoration -
                    // emoji, [表情] markers and hashtag runs. Feed it the prose only; the
                    // cache still keys on the original so callers are unaffected.
                    val source = cleanForTranslation(text)
                    if (source.isNotBlank()) {
                        runCatching { clientFor(target).translate(source).await() }
                            .getOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?.let { cache.put(key(text, target, engine), it) }
                    }
                }
            }.awaitAll()
        }
    }
}

/** True if the string contains a CJK ideograph, i.e. there is something to translate. */
internal fun hasHan(s: String): Boolean =
    s.any { it.code in 0x4E00..0x9FFF || it.code in 0x3400..0x4DBF }

/**
 * RedNote merges a whole feed card into one accessibility node whose description reads
 * "视频  <title> 来自<author> 5.3K赞" (type, title, "from" author, like count). Only the
 * title is worth translating and covering.
 *
 * ponytail: this shape is RedNote's, not a documented contract - if they change it the
 * regex stops matching and we fall back to translating the description whole.
 */
private val CARD_PREFIX = Regex("^\\s*(视频|笔记|直播|图文)\\s+")
private val CARD_SUFFIX = Regex("\\s*来自.*$")

internal fun cardTitle(desc: String): String =
    desc.replace(CARD_PREFIX, "").replace(CARD_SUFFIX, "").trim().ifEmpty { desc.trim() }

// --- input cleaning -----------------------------------------------------------

/** Emoticon tokens RedNote writes inline, e.g. "[smile R]" or "[害羞R]". */
private val BRACKET_TOKEN = Regex("""\[[^\[\]]{1,16}\]""")

/** Hashtag runs. Translated as prose they turn into word salad. */
private val HASHTAG = Regex("""#[^#\s]{1,32}""")

/** Three or more of the same character - the model loops on these and never stops. */
private val REPEATS = Regex("""(.)\1{2,}""")

private val WHITESPACE = Regex("""\s+""")

private fun stripEmoji(s: String): String {
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        val width = Character.charCount(cp)
        val decorative = cp in 0x1F000..0x1FAFF ||   // emoji planes
            cp in 0x2600..0x27BF ||                  // misc symbols, dingbats
            cp in 0x2B00..0x2BFF ||                  // arrows, stars
            cp == 0xFE0F || cp == 0x20E3             // variation / keycap
        if (!decorative) sb.appendRange(s, i, i + width)
        i += width
    }
    return sb.toString()
}

/**
 * Strips what the on-device model cannot handle, leaving the sentence behind. Returns blank
 * when nothing but decoration was there, which tells the caller to skip the label entirely
 * rather than cover the original with junk.
 */
internal fun cleanForTranslation(raw: String): String =
    stripEmoji(raw)
        .replace(BRACKET_TOKEN, " ")
        .replace(HASHTAG, " ")
        .replace(REPEATS) { it.groupValues[1].repeat(2) }
        .replace(WHITESPACE, " ")
        .trim()
