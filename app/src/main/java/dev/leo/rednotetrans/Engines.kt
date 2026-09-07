package dev.leo.rednotetrans

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val UA =
    "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/131.0.0.0 Mobile Safari/537.36"

/**
 * Google's web translate endpoint - the one browser clients call, no API key. It answers from
 * the same cloud model the desktop site uses, so slang comes back intact where the on-device
 * model mangles it.
 *
 * ponytail: undocumented and browser-facing. It bot-blocks by IP without warning, and when it
 * does it answers HTTP 200 with an HTML "Sorry" page rather than an error - hence the explicit
 * HTML check. Two hosts are tried because they block independently, and the caller falls back
 * to the on-device model when both refuse.
 */
object GoogleWebEngine {

    /** Why the last call failed, for the UI. Null once one succeeds. */
    @Volatile
    var lastError: String? = null
        private set

    private val HOSTS = listOf(
        "https://clients5.google.com/translate_a/single?client=dict-chrome-ex",
        "https://translate.googleapis.com/translate_a/single?client=gtx",
    )

    fun translate(text: String, target: String): String? {
        var why = "No response"
        for (host in HOSTS) {
            val body = try {
                fetch("$host&sl=auto&tl=$target&dt=t&q=" + URLEncoder.encode(text, "UTF-8"))
            } catch (e: Exception) {
                why = e.message ?: e.javaClass.simpleName
                continue
            }
            val out = parse(body)
            if (out != null) {
                lastError = null
                return out
            }
            why = if (body.trimStart().startsWith("<")) "Blocked by Google (bot check)"
            else "Unexpected response"
        }
        lastError = why
        return null
    }

    private fun fetch(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6000
            readTimeout = 6000
            setRequestProperty("User-Agent", UA)
        }
        try {
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    /** Shape: [[["translated","source",...],["more","source",...]],null,"zh-CN",...] */
    internal fun parse(body: String): String? {
        // A block page arrives as HTML with a 200, which would otherwise look like success.
        if (body.trimStart().startsWith("<")) return null
        return runCatching {
            val chunks = JSONArray(body).optJSONArray(0) ?: return null
            val sb = StringBuilder()
            for (i in 0 until chunks.length()) {
                chunks.optJSONArray(i)?.optString(0)?.let(sb::append)
            }
            sb.toString().trim().ifEmpty { null }
        }.getOrNull()
    }
}

/**
 * DeepSeek, via its OpenAI-compatible chat API. Trained heavily on both Chinese and English,
 * so it handles the cultural register of RedNote posts rather than translating them literally.
 *
 * Batched: a feed screen holds ~20 unique strings, and one request for all of them is both
 * far quicker and far cheaper than twenty.
 */
object DeepSeekEngine {

    /** Why the last call failed, for the UI. Null once one succeeds. */
    @Volatile
    var lastError: String? = null
        private set

    private const val ENDPOINT = "https://api.deepseek.com/chat/completions"
    private const val MODEL = "deepseek-v4-flash"
    const val BATCH_MAX = 40

    private val ENGLISH_NAME = mapOf(
        "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German",
        "pt" to "Portuguese", "it" to "Italian", "ru" to "Russian", "ja" to "Japanese",
        "ko" to "Korean", "vi" to "Vietnamese", "th" to "Thai", "id" to "Indonesian",
        "hi" to "Hindi", "ar" to "Arabic",
    )

    private fun prompt(target: String) =
        "You translate Chinese social media posts into ${ENGLISH_NAME[target] ?: target}. " +
            "Keep the tone, slang and cultural nuance; do not explain or annotate. " +
            "Each input line is an index, a tab, then the text. Reply with exactly one line " +
            "per input, in the same order, formatted as the index, a tab, then the " +
            "translation only. No preamble, no numbering other than the given index."

    /** Returns original text to translation, for whatever the model returned in order. */
    fun translate(texts: List<String>, target: String, apiKey: String): Map<String, String>? {
        if (texts.isEmpty()) return emptyMap()

        val numbered = texts.mapIndexed { i, t -> "$i\t${t.replace('\n', ' ')}" }.joinToString("\n")
        val payload = JSONObject()
            // v4-flash is the current cheap tier; swap to "deepseek-v4-pro" for the
            // stronger model at roughly 3x the cost.
            .put("model", MODEL)
            .put("stream", false)
            // DeepSeek's own guidance for translation work.
            .put("temperature", 1.3)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", prompt(target)))
                    .put(JSONObject().put("role", "user").put("content", numbered)),
            )

        val reply = try {
            post(payload.toString(), apiKey)
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            return null
        }
        val out = parse(reply, texts)
        lastError = if (out == null) "Reply could not be read" else null
        return out
    }

    private fun post(json: String, apiKey: String): String {
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            // Generous on purpose: a request that times out is billed exactly like one that
            // answers, so giving up early wastes money and gains nothing. Nothing waits on
            // this - on-device labels are already on screen by the time it runs.
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("User-Agent", UA)
        }
        try {
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code == 200) return conn.inputStream.bufferedReader().readText()

            // The body carries the actual reason - bad key, no credit, unknown model - and
            // hiding it is what made this fail silently for so long.
            val detail = runCatching {
                JSONObject(conn.errorStream.bufferedReader().readText())
                    .getJSONObject("error").getString("message")
            }.getOrNull()
            throw IllegalStateException(
                when (code) {
                    401 -> "Key rejected (401)"
                    402 -> "Out of credit (402)"
                    429 -> "Rate limited (429)"
                    else -> detail ?: "HTTP $code"
                }
            )
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Pairs each returned line back to its source by the index the prompt asked for, so a
     * dropped or reordered line cannot shift every later translation onto the wrong text.
     */
    internal fun parse(responseBody: String, texts: List<String>): Map<String, String>? =
        runCatching {
            val content = JSONObject(responseBody)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            val out = HashMap<String, String>()
            for (line in content.lineSequence()) {
                val tab = line.indexOf('\t').takeIf { it > 0 } ?: continue
                val index = line.substring(0, tab).trim().toIntOrNull() ?: continue
                val translated = line.substring(tab + 1).trim()
                if (translated.isNotEmpty()) texts.getOrNull(index)?.let { out[it] = translated }
            }
            out.ifEmpty { null }
        }.getOrNull()
}
