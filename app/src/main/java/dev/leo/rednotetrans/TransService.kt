package dev.leo.rednotetrans

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Reads RedNote's on-screen text through the accessibility tree, translates it, and covers
 * each original string with an opaque label carrying the translation - the closest Android
 * gets to a browser extension rewriting the DOM.
 *
 * The overlay is TYPE_ACCESSIBILITY_OVERLAY, so it needs no "draw over other apps"
 * permission, and NOT_TOUCHABLE, so every tap and swipe passes straight through to RedNote.
 */
class TransService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {

    private companion object {
        const val TARGET_PKG = "com.xingin.xhs"
        const val DEBOUNCE_MS = 280L

        /**
         * A playing video fires content-changed faster than the debounce window; without
         * this ceiling the scan is postponed forever and nothing ever draws.
         */
        const val MAX_STALE_MS = 700L

        const val MAX_LABELS = 80   // on-device translation is cheap; cover a dense feed
        const val MIN_W = 24
        const val MIN_H = 12
        const val MAX_DEPTH = 60

        /**
         * A merged feed card is far taller than a label; below this a content-desc node is
         * just a button ("share", "comment") and can be covered where it sits.
         */
        const val CARD_MIN_H_DP = 120

        /**
         * Fraction of a grid card taken by its image. The title has no node of its own, so
         * the band has to start somewhere; this is where the text area begins.
         *
         * ponytail: a heuristic, not a measurement - a card with an unusually tall image
         * gets its bottom edge covered. Lower the overlay opacity if that bothers you.
         */
        const val IMAGE_RATIO = 0.55f

        /** Never cover less than this, or the title has nowhere to render. */
        const val MIN_BAND_DP = 40

        /**
         * A redraw only happens when something changes on screen, and a playing video fires
         * nothing, so faster sampling bought nothing. One a second, paired with the reel
         * heartbeat below, is what actually keeps colours moving with the picture.
         */
        const val SHOT_INTERVAL_MS = 1000L

        /** While a reel is open and sampling is on, re-read colours on this cadence. */
        const val RESAMPLE_MS = 1000L

        /** Sampling works on a quarter-size copy: cheap, and the downscale averages out
         *  neighbouring glyph pixels for free. */
        const val SHOT_SCALE = 4

        const val TAG = "RedNoteTrans"
    }

    private data class Item(
        val text: String,
        val bounds: Rect,
        val isCard: Boolean,
        /** Size RedNote draws this text at, in px; 0 when the view would not say. */
        val textSizePx: Float = 0f,
        /** Hard floor the drawn label may not cross - the foot of its own card. */
        val limit: Int = Int.MAX_VALUE,
    )

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences

    private var overlay: FrameLayout? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scanTask = Runnable { scan() }
    private var lastScanAt = 0L

    /** Class of the RedNote screen currently in front, for per-page overlay colours. */
    private var currentPage: String? = null

    /** Most recent quarter-size screen copy, for sampling label backgrounds. */
    private var shot: Bitmap? = null
    private var lastShotAt = 0L

    private val density get() = resources.displayMetrics.density

    override fun onServiceConnected() {
        wm = getSystemService(WindowManager::class.java)
        prefs = Prefs.of(this)
        prefs.registerOnSharedPreferenceChangeListener(this)

        val view = FrameLayout(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Node bounds are absolute screen coordinates; without opting out of inset
            // fitting the system pushes this window below the status bar and every label
            // lands that far too low.
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
        }

        wm.addView(view, lp)
        overlay = view

        Translator.loadCache(this)

        // Pull the language pair down now rather than on the first note the user opens.
        scope.launch { Translator.ensureModel(prefs.lang) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!prefs.enabled) { clear(); return }

        // Events arrive from every app so that we notice RedNote losing the foreground -
        // otherwise labels keep floating over whatever replaced it, including the
        // notification shade. Only RedNote's tree is ever read.
        val pkg = event?.packageName?.toString()

        if (pkg != null && pkg != TARGET_PKG) {
            // Whose event it is says nothing useful: the status bar churns while RedNote is
            // still right there, and our own overlay reports its every change. What matters
            // is only whether RedNote still holds the front - that covers another app
            // opening, the notification shade, and this app's own settings screen alike.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                !isTargetInFront()
            ) {
                handler.removeCallbacks(scanTask)
                clear()
                // RedNote is gone, so the pixels sampled from it have no further use.
                dropShot()
            }
            return
        }

        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // A window event names a view class as often as an activity (dialogs, popups).
            // Only RedNote's own classes identify a page; anything else would blank it out.
            event.className?.toString()
                ?.takeIf { it.startsWith("com.xingin") }
                ?.let { currentPage = it }
        }

        handler.removeCallbacks(scanTask)
        when (event?.eventType) {
            // Content moved: every label's position is now wrong, so drop them at once and
            // wait for the motion to settle before measuring again.
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                clear()
                handler.postDelayed(scanTask, DEBOUNCE_MS)
            }
            else -> {
                val stale = SystemClock.uptimeMillis() - lastScanAt > MAX_STALE_MS
                handler.postDelayed(scanTask, if (stale) 0L else DEBOUNCE_MS)
            }
        }
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        if (!prefs.enabled) {
            clear()
        } else {
            scope.launch { Translator.ensureModel(prefs.lang) }
            handler.post(scanTask)
        }
    }

    override fun onInterrupt() = clear()

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        Translator.saveCache(this)
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
        dropShot()
        return super.onUnbind(intent)
    }

    // --- scanning -----------------------------------------------------------

    private fun scan() {
        lastScanAt = SystemClock.uptimeMillis()
        work?.cancel()
        if (!prefs.enabled) { clear(); return }

        val items = measure() ?: return clear()
        val target = prefs.lang

        // Draw whatever is already cached right now, so re-reading a note is instant.
        withSample { drawCached(items, target) }

        // Video moves without firing accessibility events, so nothing would ask for a
        // fresh sample. This heartbeat is what makes matched colours track the picture
        // rather than only updating when the screen is touched.
        if (prefs.sampleBg && isReelPage() && screenOn()) {
            handler.removeCallbacks(scanTask)
            handler.postDelayed(scanTask, RESAMPLE_MS)
        }

        // Then fill in the rest. One translation per *unique* string, not per label.
        val pending = items.map { it.text }.distinct()
            .filter { Translator.cachedExact(it, target, prefs.engine) == null }
        if (pending.isEmpty()) return

        // The local pass is cancellable: it is cheap, and a scroll makes its answer stale.
        work = scope.launch {
            Translator.fillOnDevice(pending, target)
            // The feed can scroll or reflow while translation runs, which would land every
            // label at a position that is no longer true. Re-measure instead of reusing.
            val afterLocal = measure() ?: return@launch clear()
            withSample { drawCached(afterLocal, target) }
        }

        // The cloud pass deliberately is not: cancelling it would abandon a request the
        // provider has already billed, and the next scan would pay for the same answer
        // over again. It writes to the cache and redraws if the screen is still there.
        scope.launch {
            if (Translator.fillCloud(pending, target, this@TransService)) {
                Translator.saveCache(this@TransService)
                val afterCloud = measure() ?: return@launch
                withSample { drawCached(afterCloud, target) }
            }
        }
    }

    /**
     * RedNote truncates a title node with an ellipsis while the card description carries it
     * in full, so the two never compare equal. Match on the opening characters instead.
     */
    private fun sameTitle(a: String, b: String): Boolean {
        fun head(s: String) = s.filter(Char::isLetterOrDigit).take(8)
        return head(a).isNotEmpty() && head(a) == head(b)
    }

    /**
     * The full-screen reel feed. Rounded corners and background sampling apply only here -
     * on the grid a rounded, colour-matched label reads as a sticker on a tidy card, while
     * over video it is what makes the label disappear into the picture.
     */
    private fun isReelPage(): Boolean =
        currentPage?.contains("DetailFeedActivity", ignoreCase = true) == true

    /**
     * Grabs a quarter-size copy of the screen, then draws. Colours have to be read from the
     * pixels actually behind each label - there is no way to ask another app what it painted.
     * Nothing is stored or sent anywhere; the copy lives in memory until the next one.
     */
    private fun withSample(then: () -> Unit) {
        val now = SystemClock.uptimeMillis()
        if (!prefs.sampleBg ||
            // Sampling is a reel-only feature, so the grid feed should never be captured.
            !isReelPage() ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            now - lastShotAt < SHOT_INTERVAL_MS
        ) {
            then()
            return
        }
        lastShotAt = now

        runCatching {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        runCatching {
                            val buffer = result.hardwareBuffer
                            val full = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            buffer.close()
                            if (full != null) {
                                // takeScreenshot hands back a HARDWARE bitmap, which a
                                // software Canvas refuses to draw and getPixel cannot read.
                                // copy() is the GPU readback that makes it addressable.
                                val software = full.copy(Bitmap.Config.ARGB_8888, false)
                                full.recycle()
                                if (software != null) {
                                    val small = Bitmap.createScaledBitmap(
                                        software,
                                        software.width / SHOT_SCALE,
                                        software.height / SHOT_SCALE,
                                        true,
                                    )
                                    software.recycle()
                                    shot?.recycle()
                                    shot = small
                                }
                            }
                        }.onFailure { Log.w(TAG, "screenshot decode failed", it) }
                        then()
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "screenshot failed, code=$errorCode")
                        then()
                    }
                },
            )
        }.onFailure {
            Log.w(TAG, "takeScreenshot threw", it)
            then()
        }
    }

    /**
     * Five colours across a label, each read just above and just below the text row so the
     * glyphs themselves are not what gets sampled.
     */
    private fun sample(rect: Rect): IntArray? {
        val bmp = shot?.takeIf { !it.isRecycled } ?: return null
        val gap = (5 * density).toInt()

        fun px(x: Int, y: Int): Int? = runCatching {
            bmp.getPixel(
                (x / SHOT_SCALE).coerceIn(0, bmp.width - 1),
                (y / SHOT_SCALE).coerceIn(0, bmp.height - 1),
            )
        }.getOrNull()

        val step = rect.width() / 6
        return IntArray(5) { i ->
            val x = rect.left + step * (i + 1)
            mix(px(x, rect.top - gap), px(x, rect.bottom + gap)) ?: return null
        }
    }

    private fun mix(a: Int?, b: Int?): Int? = when {
        a != null && b != null -> Color.rgb(
            (Color.red(a) + Color.red(b)) / 2,
            (Color.green(a) + Color.green(b)) / 2,
            (Color.blue(a) + Color.blue(b)) / 2,
        )
        else -> a ?: b
    }

    /** A heartbeat that samples colours off a screen nobody is looking at is pure drain. */
    private fun screenOn(): Boolean =
        getSystemService(PowerManager::class.java)?.isInteractive != false

    private fun isTargetInFront(): Boolean =
        runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull() == TARGET_PKG

    /** Current on-screen strings worth covering, or null if RedNote is not in front. */
    private fun measure(): List<Item>? {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return null
        if (root.packageName?.toString() != TARGET_PKG) return null
        val raw = ArrayList<Item>()
        val anchors = ArrayList<Rect>()
        collect(root, raw, anchors, 0)
        return if (raw.isEmpty()) null else place(raw, anchors)
    }

    private fun drawCached(items: List<Item>, target: String) = draw(
        items.mapNotNull { i ->
            Translator.cached(i.text, target, prefs.engine)?.let { i to it }
        }
            // A title of only punctuation or emoji translates to junk like "?" - covering
            // the original with that is worse than leaving it alone.
            .filter { (_, translated) -> translated.any(Char::isLetterOrDigit) }
    )

    private fun collect(
        node: AccessibilityNodeInfo?,
        out: MutableList<Item>,
        anchors: MutableList<Rect>,
        depth: Int,
    ) {
        if (node == null || depth > MAX_DEPTH || out.size >= MAX_LABELS) return

        val r = Rect().also(node::getBoundsInScreen)
        if (r.width() >= MIN_W && r.height() >= MIN_H && node.isVisibleToUser) {
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()

            // Any text at all marks where a card's text area begins, whatever language it
            // is in. Collecting only Chinese ones left cards with a Latin username with no
            // floor to sit on, so their band dropped to the foot of the card and spilled
            // onto the post below.
            if (!text.isNullOrEmpty()) anchors += Rect(r)

            when {
                !text.isNullOrEmpty() && hasHan(text) && text.length >= prefs.minLen ->
                    out += Item(text, r, false, textSizeOf(node))
                // RedNote publishes feed cards as one merged node: the title lives only in
                // the description, so text-only scanning finds nothing on the main feed.
                // Smaller described nodes are badges and tags stamped on the artwork -
                // covering those just litters the image.
                !desc.isNullOrEmpty() && hasHan(desc) && r.height() > CARD_MIN_H_DP * density ->
                    out += Item(cardTitle(desc), r, true)
            }
        }
        for (i in 0 until node.childCount) collect(node.getChild(i), out, anchors, depth + 1)
    }

    /**
     * The size RedNote actually draws a string at. Matching it is what stops the overlay
     * reading as a foreign layer pasted on top - guessing a size makes every label look
     * wrong even when the translation is right.
     */
    private fun textSizeOf(node: AccessibilityNodeInfo): Float = runCatching {
        node.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_RENDERING_INFO_KEY, Bundle())
        node.extraRenderingInfo?.textSizeInPx ?: 0f
    }.getOrDefault(0f).let { if (it > 0f) it else 0f }

    /**
     * A merged card's bounds are the whole card, image included, so its title cannot be
     * covered in place. Anchor a caption band to the foot of the card instead - just above
     * the topmost real text node inside it, which is the author row RedNote draws under
     * the title.
     */
    private fun place(raw: List<Item>, anchors: List<Rect>): List<Item> {
        val minBand = (MIN_BAND_DP * density).toInt()
        val out = ArrayList<Item>(raw.size)

        for (item in raw) {
            if (!item.isCard) { out += item; continue }
            val inner = raw.filter { !it.isCard && item.bounds.contains(it.bounds) }

            // On some card styles the title is a real node after all, and is already being
            // covered exactly. Adding a band would stack a second copy underneath it.
            // Match only on the title itself - a merely overlapping author name is not it.
            if (inner.any { sameTitle(it.text, item.text) }) continue

            // The author row is the one reliable landmark below the title. Without it, a
            // card running off the bottom of the screen has no measurable foot, and the
            // band would spill over the navigation bar.
            // Text in the lower half of the card is its meta row; anything higher is a
            // caption burnt onto the artwork and a bad floor to measure from.
            val meta = anchors.filter {
                item.bounds.contains(it) && it.top > item.bounds.top + item.bounds.height() / 2
            }

            val screenH = resources.displayMetrics.heightPixels
            if (meta.isEmpty() && item.bounds.bottom > screenH * 0.93f) continue
            val floor = meta.minOfOrNull { it.top } ?: item.bounds.bottom
            val top = (item.bounds.top + item.bounds.height() * IMAGE_RATIO).toInt()
                .coerceAtMost(floor - minBand)
                .coerceAtLeast(item.bounds.top)

            out += item.copy(
                bounds = Rect(item.bounds.left, top, item.bounds.right, floor),
                limit = item.bounds.bottom,
            )
        }
        return out
    }

    // --- drawing ------------------------------------------------------------

    /**
     * The screen copy lives in memory only - never written to disk, never sent anywhere -
     * and is released as soon as it stops being needed.
     */
    private fun dropShot() {
        shot?.recycle()
        shot = null
        lastShotAt = 0L
    }

    private fun luminance(c: Int): Float =
        (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f

    private fun clear() {
        work?.cancel()
        overlay?.removeAllViews()
    }

    private fun draw(labels: List<Pair<Item, String>>) {
        val root = overlay ?: return
        root.removeAllViews()

        val dark = prefs.theme != Prefs.THEME_LIGHT
        val reel = isReelPage()
        val alpha = (prefs.opacity.coerceIn(0f, 1f) * 255).toInt()
        // Tint off means no box at all - just the translated text over the artwork.
        val bg = when {
            !prefs.tint -> Color.TRANSPARENT
            dark -> Color.argb(alpha, 0x1A, 0x19, 0x1F)
            else -> Color.argb(alpha, 0xFF, 0xFF, 0xFF)
        }
        val fg = if (dark) Color.WHITE else Color.rgb(0x1A, 0x19, 0x1F)
        val pad = (4 * density).toInt()
        val nudge = (prefs.cardNudge * density).toInt()
        val cardPad = (prefs.cardPad * density).toInt()
        val radiusPx = if (reel) prefs.radius * density else 0f
        val scale = prefs.textScale.coerceIn(0.5f, 2f)
        val weight = if (prefs.bold) Typeface.BOLD else Typeface.NORMAL

        val minPx = 9 * density
        val cardPx = 14 * density   // RedNote's grid titles, for bands with no node to ask

        for ((item, translated) in labels) {
            // Match the size RedNote draws the original at, and only shrink from there if
            // the translation is too long to fit the same space. Where the view would not
            // report a size, a single line of text is about 0.62 of its box height - still
            // far closer than a fixed maximum, which is what made labels look oversized.
            val maxPx = when {
                item.textSizePx > 0f -> item.textSizePx
                item.isCard -> cardPx
                else -> item.bounds.height() * 0.62f
            }.times(scale).coerceAtLeast(minPx + 1)

            // Sampled colours win over the fixed palette: a label that matches the pixels
            // under it stops reading as a panel stuck on top of the page.
            val sampled = if (reel && prefs.sampleBg && prefs.tint) sample(item.bounds) else null
            val shaded = sampled?.map { Color.argb(alpha, Color.red(it), Color.green(it), Color.blue(it)) }
            val onSampled = shaded?.let {
                if (luminance(sampled[1]) > 0.55f) Color.rgb(0x1A, 0x19, 0x1F) else Color.WHITE
            }

            val tv = TextView(this).apply {
                text = translated
                setTextColor(onSampled ?: fg)
                setTypeface(typeface, weight)
                background = if (shaded != null) {
                    GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        shaded.toIntArray(),
                    ).apply { cornerRadius = radiusPx }
                } else {
                    GradientDrawable().apply {
                        setColor(bg)
                        cornerRadius = radiusPx
                    }
                }
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPaddingRelative(pad, 0, pad, 0)
                // Autosize needs a bounded line count to solve against; without maxLines it
                // settles on a size that overflows and the tail is clipped away.
                maxLines = if (item.isCard) 3
                else (item.bounds.height() / (18 * density)).toInt().coerceAtLeast(1)
                setAutoSizeTextTypeUniformWithConfiguration(
                    minPx.toInt(), maxPx.toInt(), 1, TypedValue.COMPLEX_UNIT_PX,
                )
                breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
            }

            var height = item.bounds.height()
            var top = item.bounds.top

            if (item.isCard) {
                // A card band has no real rectangle behind it, so filling the whole guessed
                // box paints over artwork. Measure the text and cover only that much.
                tv.measure(
                    View.MeasureSpec.makeMeasureSpec(item.bounds.width(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                height = tv.measuredHeight.coerceIn(1, item.bounds.height()) + cardPad * 2
                // Sit directly on the author row. That edge is a real measurement, unlike
                // the estimated top of the text area, so the title lands under the label
                // instead of beside it. The nudge absorbs RedNote's padding above that row,
                // which is the last thing here that cannot be measured.
                // Grown symmetrically so the text stays put and only the box around it
                // changes size - which is what covers a title longer than its translation.
                top = item.bounds.bottom - height + nudge + cardPad
                // Never let a band cross the foot of its own card and land on the post
                // below, however the nudge and padding are set.
                if (top + height > item.limit) top = item.limit - height
            }

            root.addView(
                tv,
                FrameLayout.LayoutParams(item.bounds.width(), height).apply {
                    leftMargin = item.bounds.left
                    topMargin = top
                },
            )
        }
    }

}
