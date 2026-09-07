package dev.leo.rednotetrans

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import android.os.Build
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect

// --- One UI palette (see .claude/skills/oneui-design) -------------------------

private object OneUI {
    val CARD_RADIUS = 26.dp
    val MARGIN = 24.dp
    val HEADER_MAX = 200.dp
    val HEADER_MIN = 56.dp

    // Fallbacks only. On any Galaxy running One UI 5+ the scheme below comes from the
    // device's own theme instead.
    val accentLight = Color(0xFF0381FE)
    val accentDark = Color(0xFF3E91FF)
}

/**
 * The Galaxy theme (Settings > Wallpaper and style > Color palette) feeds Android's dynamic
 * colour scheme, so taking the palette from there is what makes the app match the phone.
 *
 * One UI structure is kept on top of it: window background darker than the cards in light
 * mode, true black in dark mode for the AMOLED panel.
 */
private class Palette(scheme: ColorScheme, dark: Boolean) {
    val bg = if (dark) Color.Black else scheme.surfaceContainer
    val card = if (dark) scheme.surfaceContainerLow else scheme.surfaceContainerLowest
    val accent = scheme.primary
    val text = scheme.onSurface
    val sub = scheme.onSurfaceVariant
}

@Composable
private fun galaxyScheme(dark: Boolean): ColorScheme {
    val ctx = LocalContext.current
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = OneUI.accentDark)
        else -> lightColorScheme(primary = OneUI.accentLight)
    }
}

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Screen() }
    }
}

@Composable
private fun Screen() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs.of(ctx) }
    val dark = isSystemInDarkTheme()
    val scheme = galaxyScheme(dark)
    val p = Palette(scheme, dark)

    var on by remember { mutableStateOf(prefs.enabled) }
    var lang by remember { mutableStateOf(prefs.lang) }
    var opacity by remember { mutableFloatStateOf(prefs.opacity) }
    var theme by remember { mutableStateOf(prefs.theme) }
    var nudge by remember { mutableFloatStateOf(prefs.cardNudge) }
    var tint by remember { mutableStateOf(prefs.tint) }
    var cardPad by remember { mutableFloatStateOf(prefs.cardPad) }
    var radius by remember { mutableFloatStateOf(prefs.radius) }
    var textScale by remember { mutableFloatStateOf(prefs.textScale) }
    var minLen by remember { mutableFloatStateOf(prefs.minLen.toFloat()) }
    var bold by remember { mutableStateOf(prefs.bold) }
    var sampleBg by remember { mutableStateOf(prefs.sampleBg) }
    var engine by remember { mutableStateOf(prefs.engine) }
    var keyLabel by remember { mutableStateOf(SecretStore.masked(ctx)) }
    var keyDialog by remember { mutableStateOf<String?>(null) }
    var authError by remember { mutableStateOf<String?>(null) }
    var showDisclaimer by remember { mutableStateOf(false) }
    var showHowTo by remember { mutableStateOf(false) }
    var cloudError by remember { mutableStateOf(Translator.cloudError) }
    var serviceOn by remember { mutableStateOf(isServiceEnabled(ctx)) }
    var modelState by remember { mutableStateOf(Translator.model) }

    // Nothing can be translated until the language pair is on the device, so fetch it as
    // soon as the screen opens and whenever the target language changes.
    LaunchedEffect(lang) {
        if (Translator.model != Translator.Model.READY) modelState = Translator.Model.DOWNLOADING
        Translator.ensureModel(lang)
        modelState = Translator.model
    }

    // The user leaves to Settings to grant access, so re-check every time we come back.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                serviceOn = isServiceEnabled(ctx)
                cloudError = Translator.cloudError
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val travelPx = with(density) { (OneUI.HEADER_MAX - OneUI.HEADER_MIN).toPx() }

    // 0 = title large and bottom-left, 1 = title small and centered in the bar.
    val collapse by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / travelPx).coerceIn(0f, 1f)
        }
    }

    MaterialTheme(colorScheme = scheme) {
    Box(Modifier.fillMaxSize().background(p.bg)) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                top = OneUI.HEADER_MAX,
                bottom = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SectionHeader("Overlay", p)
                Card(p) {
                    StatusRow(serviceOn, p) {
                        ctx.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    Divider(p)
                    ToggleRow(
                        title = "Translate live",
                        subtitle = "Cover Chinese text with the translation as you scroll",
                        checked = on,
                        palette = p,
                    ) { on = it; prefs.enabled = it }
                }
            }

            item {
                SectionHeader("Translation", p)
                Card(p) {
                    EngineRow(engine, cloudError, p) {
                        engine = it
                        prefs.engine = it
                        if (it == Prefs.ENGINE_DEEPSEEK && !prefs.deepseekWarned) {
                            showDisclaimer = true
                        }
                    }
                    Divider(p)
                    LanguageRow(lang, p) { lang = it; prefs.lang = it }
                    Divider(p)
                    if (engine == Prefs.ENGINE_DEEPSEEK) {
                        ApiKeyRow(keyLabel, authError, p) {
                            authError = null
                            unlock(ctx, "Unlock API key") { ok, err ->
                                if (ok) keyDialog = SecretStore.load(ctx).orEmpty()
                                else authError = err
                            }
                        }
                        Divider(p)
                    }
                    ModelRow(modelState, p)
                }
            }

            item {
                SectionHeader("Appearance", p)
                Card(p) {
                    LabelPreview(p)
                    Divider(p)
                    ThemeRow(theme, p) { theme = it; prefs.theme = it }
                    Divider(p)
                    ToggleRow(
                        title = "Background box",
                        subtitle = "Off leaves bare text over the artwork",
                        checked = tint,
                        palette = p,
                    ) { tint = it; prefs.tint = it }
                    Divider(p)
                    SliderRow(
                        title = "Overlay opacity",
                        subtitle = "Lower it to see the original text underneath",
                        value = opacity,
                        range = 0.5f..1f,
                        steps = 0,
                        readout = "${(opacity * 100).toInt()}%",
                        p = p,
                    ) { opacity = it; prefs.opacity = it }
                    Divider(p)
                    SliderRow(
                        title = "Corner radius",
                        subtitle = "Reels only. Labels stay square on the main feed",
                        value = radius,
                        range = 0f..20f,
                        steps = 19,
                        readout = "${radius.toInt()} dp",
                        p = p,
                    ) { radius = it; prefs.radius = it }
                    Divider(p)
                    ToggleRow(
                        title = "Bold text",
                        subtitle = "Easier to read over busy images",
                        checked = bold,
                        palette = p,
                    ) { bold = it; prefs.bold = it }
                    Divider(p)
                    ToggleRow(
                        title = "Match background",
                        subtitle = "Reels only. Reads the colours under each label and blends " +
                            "into them; nothing is saved or sent",
                        checked = sampleBg,
                        palette = p,
                    ) { sampleBg = it; prefs.sampleBg = it }
                    Divider(p)
                    ResetRow(p) {
                        prefs.resetAppearance()
                        theme = prefs.theme
                        tint = prefs.tint
                        opacity = prefs.opacity
                        radius = prefs.radius
                        textScale = prefs.textScale
                        bold = prefs.bold
                        minLen = prefs.minLen.toFloat()
                        sampleBg = prefs.sampleBg
                        cardPad = prefs.cardPad
                        nudge = prefs.cardNudge
                    }
                }
            }

            item {
                SectionHeader("Text", p)
                Card(p) {
                    SliderRow(
                        title = "Text size",
                        subtitle = "Scales the size matched from RedNote",
                        value = textScale,
                        range = 0.7f..1.4f,
                        steps = 13,
                        readout = "${(textScale * 100).toInt()}%",
                        p = p,
                    ) { textScale = it; prefs.textScale = it }
                    Divider(p)
                    SliderRow(
                        title = "Minimum length",
                        subtitle = "Skip short strings. Raise it to stop translating usernames",
                        value = minLen,
                        range = 1f..10f,
                        steps = 8,
                        readout = if (minLen <= 1f) "Off" else "${minLen.toInt()} chars",
                        p = p,
                    ) { minLen = it; prefs.minLen = it.toInt() }
                }
            }

            item {
                SectionHeader("Feed cards", p)
                Card(p) {
                    SliderRow(
                        title = "Feed title position",
                        subtitle = "Grid titles have no exact position to read. Nudge them " +
                            "onto the text",
                        value = nudge,
                        range = -48f..48f,
                        steps = 23,
                        readout = "${nudge.toInt()} dp",
                        p = p,
                    ) { nudge = it; prefs.cardNudge = it }
                    Divider(p)
                    SliderRow(
                        title = "Background size",
                        subtitle = "Grows the box on the main feed without changing the text",
                        value = cardPad,
                        range = 0f..32f,
                        steps = 15,
                        readout = "+${cardPad.toInt()} dp",
                        p = p,
                    ) { cardPad = it; prefs.cardPad = it }
                }
            }

            item {
                SectionHeader("How it works", p)
                Card(p) {
                    Column(Modifier.padding(horizontal = OneUI.MARGIN, vertical = 20.dp)) {
                        Body(
                            "RedNote's own text is read through Android's accessibility tree, sent to " +
                                "whichever engine you picked, and drawn back over the original " +
                                "in place. Touches pass straight through, so the app behaves " +
                                "normally and its recommendation feed is untouched.",
                            p,
                        )
                        Spacer(Modifier.height(14.dp))
                        Body(
                            "On device runs offline and free but is weakest on slang. Google " +
                                "uses the same cloud endpoint browser clients call. DeepSeek is " +
                                "strongest on Chinese nuance and bills per token. If a cloud " +
                                "engine cannot answer, on device covers for it.",
                            p,
                        )
                        Spacer(Modifier.height(14.dp))
                        Body(
                            "Text baked into images and video is not translated - only real text. " +
                                "This service is restricted to RedNote and cannot read any other app.",
                            p,
                            color = p.sub,
                        )
                    }
                }
            }
        }

        CollapsingHeader(collapse, p)
    }

    if (showDisclaimer) {
        DeepSeekDisclaimer(
            palette = p,
            onHowTo = { showHowTo = true },
            onDismiss = { suppress ->
                if (suppress) prefs.deepseekWarned = true
                showDisclaimer = false
            },
            onSetKey = { suppress ->
                if (suppress) prefs.deepseekWarned = true
                showDisclaimer = false
                unlock(ctx, "Unlock API key") { ok, err ->
                    if (ok) keyDialog = SecretStore.load(ctx).orEmpty() else authError = err
                }
            },
        )
    }

    if (showHowTo) HowToGetKey(p) { showHowTo = false }

    keyDialog?.let { current ->
        ApiKeyDialog(
            initial = current,
            palette = p,
            onDismiss = { keyDialog = null },
            onClear = {
                SecretStore.clear(ctx)
                keyLabel = SecretStore.masked(ctx)
                keyDialog = null
            },
            onSave = { entered ->
                if (entered.isBlank()) SecretStore.clear(ctx)
                else SecretStore.save(ctx, entered.trim())
                keyLabel = SecretStore.masked(ctx)
                keyDialog = null
            },
            onHowTo = { showHowTo = true },
        )
    }
    }
}

/**
 * The key is usable by the background service without a prompt - it has to be, the overlay
 * cannot stop to ask. Authentication guards reading and changing it here, where a person is
 * present to answer.
 */
private fun unlock(ctx: Context, title: String, done: (Boolean, String?) -> Unit) {
    val activity = ctx as? FragmentActivity ?: return done(false, "No activity")
    val allowed = BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

    if (BiometricManager.from(ctx).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
        return done(false, "Set up a screen lock to store an API key")
    }

    BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(ctx),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                done(true, null)

            override fun onAuthenticationError(code: Int, message: CharSequence) =
                done(false, message.toString())
        },
    ).authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Fingerprint, face or device PIN")
            .setAllowedAuthenticators(allowed)
            .build()
    )
}

// --- One UI collapsing large title -------------------------------------------

@Composable
private fun CollapsingHeader(collapse: Float, p: Palette) {
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val height = lerp(OneUI.HEADER_MAX, OneUI.HEADER_MIN, collapse)

    Box(
        Modifier
            .fillMaxWidth()
            .height(height + statusBar)
            .background(p.bg)
            .padding(top = statusBar),
    ) {
        // The title travels from bottom-left (34sp) to centered (20sp) as you scroll.
        Text(
            text = "RedNote Translate",
            color = p.text,
            fontSize = mix(34f, 20f, collapse).sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier
                // Bias interpolation slides the title from bottom-left to centre without
                // ever swapping labels or snapping between two alignments.
                .align(BiasAlignment(mix(-1f, 0f, collapse), mix(1f, 0f, collapse)))
                .padding(
                    horizontal = lerp(OneUI.MARGIN, 0.dp, collapse),
                    vertical = lerp(20.dp, 0.dp, collapse),
                ),
        )
    }
}

private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

// --- One UI building blocks ---------------------------------------------------

@Composable
private fun SectionHeader(text: String, p: Palette) {
    Text(
        text = text,
        color = p.accent,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = OneUI.MARGIN, bottom = 8.dp),
    )
}

@Composable
private fun Card(p: Palette, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = OneUI.MARGIN),
        shape = RoundedCornerShape(OneUI.CARD_RADIUS),
        color = p.card,
    ) {
        Column(content = content)
    }
}


@Composable
private fun Divider(p: Palette) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = OneUI.MARGIN)
            .height(1.dp)
            .background(p.sub.copy(alpha = 0.18f)),
    )
}

@Composable
private fun Title(text: String, p: Palette) =
    Text(text, color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)

@Composable
private fun Body(text: String, p: Palette, color: Color = p.text) =
    Text(text, color = color, fontSize = 15.sp, lineHeight = 22.sp)

@Composable
private fun Sub(text: String, p: Palette) =
    Text(text, color = p.sub, fontSize = 14.sp, lineHeight = 19.sp)

@Composable
private fun StatusRow(serviceOn: Boolean, p: Palette, onFix: () -> Unit) {
    Column(Modifier.padding(horizontal = OneUI.MARGIN, vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (serviceOn) Color(0xFF14C46A) else Color(0xFFFF5B5B)),
            )
            Spacer(Modifier.size(10.dp))
            Title(if (serviceOn) "Service running" else "Service off", p)
        }
        Spacer(Modifier.height(6.dp))
        Sub(
            if (serviceOn) "RedNote Translate is allowed to read RedNote."
            else "Turn on RedNote Translate under Settings > Accessibility > Installed apps.",
            p,
        )
        if (!serviceOn) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onFix,
                shape = RoundedCornerShape(OneUI.CARD_RADIUS),
                colors = ButtonDefaults.buttonColors(containerColor = p.accent),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Open Accessibility settings", fontSize = 16.sp) }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    palette: Palette,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = OneUI.MARGIN, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Title(title, palette)
            Spacer(Modifier.height(4.dp))
            Sub(subtitle, palette)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = palette.accent),
        )
    }
}

/** Real before and after captures, dragged apart in place. */
@Composable
private fun LabelPreview(p: Palette) {
    Column(Modifier.padding(horizontal = OneUI.MARGIN, vertical = 20.dp)) {
        Title("Preview", p)
        Spacer(Modifier.height(4.dp))
        Sub("Drag each one to compare. Rounded corners and matched colours are reels only", p)

        Spacer(Modifier.height(16.dp))
        Sub("Main feed", p)
        Spacer(Modifier.height(8.dp))
        Compare(R.drawable.feed_before, R.drawable.feed_after, 545f / 917f)

        Spacer(Modifier.height(18.dp))
        Sub("Reel", p)
        Spacer(Modifier.height(8.dp))
        Compare(R.drawable.reel_before, R.drawable.reel_after, 1080f / 700f)
    }
}

@Composable
private fun Compare(before: Int, after: Int, ratio: Float) {
    var frac by remember { mutableFloatStateOf(0.5f) }
    // The divider is positioned in pixels, which only the measured box knows.
    var boxWidth by remember { mutableIntStateOf(0) }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(14.dp))
            .onSizeChanged { boxWidth = it.width }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    frac = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            },
    ) {
        Image(
            painterResource(before),
            "Before translation",
            Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Image(
            painterResource(after),
            "After translation",
            Modifier
                .fillMaxSize()
                // Reveals the treated capture from the handle rightwards, so both halves
                // stay in register rather than one being scaled to fit a shrinking box.
                .drawWithContent {
                    clipRect(left = size.width * frac) { this@drawWithContent.drawContent() }
                },
            contentScale = ContentScale.Crop,
        )
        Box(
            Modifier
                .fillMaxHeight()
                .width(2.dp)
                .align(Alignment.CenterStart)
                .offset { IntOffset((boxWidth * frac).toInt(), 0) }
                .background(Color.White),
        )
    }
}

@Composable
private fun ResetRow(p: Palette, onReset: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onReset)
            .padding(horizontal = OneUI.MARGIN, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Title("Reset appearance", p)
            Spacer(Modifier.height(4.dp))
            Sub("Put every look and position setting back to its default", p)
        }
        Text("Reset", color = p.accent, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DeepSeekDisclaimer(
    palette: Palette,
    onHowTo: () -> Unit,
    onDismiss: (Boolean) -> Unit,
    onSetKey: (Boolean) -> Unit,
) {
    var suppress by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { onDismiss(suppress) },
        shape = RoundedCornerShape(OneUI.CARD_RADIUS),
        containerColor = palette.card,
        title = { Text("Before you use DeepSeek", color = palette.text) },
        text = {
            Column {
                Body("The text on screen leaves your phone.", palette)
                Spacer(Modifier.height(10.dp))
                Sub(
                    "Whatever RedNote shows you is sent to DeepSeek servers to be translated. " +
                        "DeepSeek is a Chinese company and its API is subject to its own privacy " +
                        "policy, not this app.",
                    palette,
                )
                Spacer(Modifier.height(12.dp))
                Body("It costs money, but very little.", palette)
                Spacer(Modifier.height(10.dp))
                Sub(
                    "Billing is per token. New accounts start with free credit, and browsing a " +
                        "feed uses a tiny fraction of it. You are responsible for the spend on " +
                        "your own key.",
                    palette,
                )
                Spacer(Modifier.height(12.dp))
                Body("It needs a connection.", palette)
                Spacer(Modifier.height(10.dp))
                Sub(
                    "With no signal, or if the key is wrong, translation falls back to the " +
                        "on-device model automatically.",
                    palette,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "How to set up an API key",
                    color = palette.accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable(onClick = onHowTo),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { suppress = !suppress },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = suppress,
                        onCheckedChange = { suppress = it },
                        colors = CheckboxDefaults.colors(checkedColor = palette.accent),
                    )
                    Sub("Do not show this again", palette)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSetKey(suppress) }) { Text("Set up key") } },
        dismissButton = { TextButton(onClick = { onDismiss(suppress) }) { Text("Later") } },
    )
}

/**
 * The steps stay in-app so they work with no connection, but the sign-up link opens a
 * browser - an account cannot be created from in here anyway.
 */
@Composable
private fun HowToGetKey(palette: Palette, onDismiss: () -> Unit) {
    val laterSteps = listOf(
        "New accounts come with free tokens, enough to browse for a long while.",
        "Go to the API keys page from the left-hand menu.",
        "Tap Create new API key, give it any name, and copy the key. It starts with sk- and " +
            "is shown only once, so copy it before closing the box.",
        "Come back here, tap DeepSeek API key, and paste it in.",
        "If you run out later, top up on the same site. Billing is per token, and short feed " +
            "titles cost a fraction of a cent.",
    )

    val signUp = buildAnnotatedString {
        append("Open ")
        withLink(
            LinkAnnotation.Url(
                "https://platform.deepseek.com",
                TextLinkStyles(
                    SpanStyle(
                        color = palette.accent,
                        textDecoration = TextDecoration.Underline,
                    )
                ),
            )
        ) { append("platform.deepseek.com") }
        append(" and sign up. No card is needed.")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(OneUI.CARD_RADIUS),
        containerColor = palette.card,
        title = { Text("How to set up an API key", color = palette.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                StepRow(1, palette) {
                    Text(signUp, color = palette.sub, fontSize = 14.sp, lineHeight = 19.sp)
                }
                laterSteps.forEachIndexed { i, step ->
                    StepRow(i + 2, palette) { Sub(step, palette) }
                }
                Sub(
                    "Your key is encrypted on this device and sent only to DeepSeek. Clearing it " +
                        "here removes it from the phone entirely.",
                    palette,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
    )
}

@Composable
private fun StepRow(number: Int, p: Palette, content: @Composable () -> Unit) {
    Row(Modifier.padding(bottom = 14.dp)) {
        Text(
            "$number",
            color = p.accent,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(24.dp),
        )
        content()
    }
}

@Composable
private fun EngineRow(
    engine: String,
    cloudError: String?,
    p: Palette,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val options = listOf(
        Prefs.ENGINE_ON_DEVICE to "On device",
        Prefs.ENGINE_GOOGLE to "Google",
        Prefs.ENGINE_DEEPSEEK to "DeepSeek",
    )
    val label = options.firstOrNull { it.first == engine }?.second ?: engine
    val blurb = when {
        cloudError != null && engine != Prefs.ENGINE_ON_DEVICE ->
            // The reason matters: a rejected key and no signal look identical otherwise,
            // and both just look like bad translation.
            "$cloudError - on device covered for it. Retrying shortly"
        engine == Prefs.ENGINE_GOOGLE ->
            "Google web endpoint. Cloud quality, no key, needs a connection"
        engine == Prefs.ENGINE_DEEPSEEK -> "Best on Chinese slang and nuance. Needs your API key"
        else -> "ML Kit, offline and free. Weakest on slang"
    }

    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = true }
                .padding(horizontal = OneUI.MARGIN, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Title("Translated by", p)
                Spacer(Modifier.height(4.dp))
                Sub(blurb, p)
            }
            EngineIcon(engine, p.accent)
            Spacer(Modifier.size(8.dp))
            Text(label, color = p.accent, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    leadingIcon = { EngineIcon(value, p.text) },
                    onClick = { onPick(value); open = false },
                )
            }
        }
    }
}

/** Tinted to match the label beside it, so the mark reads as part of the text. */
@Composable
private fun EngineIcon(engine: String, tint: Color) {
    Image(
        painterResource(
            when (engine) {
                Prefs.ENGINE_GOOGLE -> R.drawable.ic_engine_google
                Prefs.ENGINE_DEEPSEEK -> R.drawable.ic_engine_deepseek
                else -> R.drawable.ic_engine_local
            }
        ),
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        colorFilter = ColorFilter.tint(tint),
    )
}

@Composable
private fun ApiKeyRow(keyLabel: String, error: String?, p: Palette, onTap: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = OneUI.MARGIN, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Title("DeepSeek API key", p)
            Spacer(Modifier.height(4.dp))
            Sub(error ?: "Encrypted on device. Unlock to view or change", p)
            Spacer(Modifier.height(6.dp))
            val uri = LocalUriHandler.current
            Text(
                "Check usage and billing",
                color = p.accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                // Nested clickable wins over the row's, so this opens the page rather
                // than the unlock prompt.
                modifier = Modifier.clickable {
                    uri.openUri("https://platform.deepseek.com/usage")
                },
            )
        }
        Text(keyLabel, color = p.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ApiKeyDialog(
    initial: String,
    palette: Palette,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onSave: (String) -> Unit,
    onHowTo: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    var reveal by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(OneUI.CARD_RADIUS),
        containerColor = palette.card,
        title = { Text("DeepSeek API key", color = palette.text) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    placeholder = { Text("sk-...") },
                    visualTransformation =
                        if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = reveal,
                        onCheckedChange = { reveal = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = palette.accent),
                    )
                    Spacer(Modifier.size(10.dp))
                    Sub("Show key", palette)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "How to set up an API key",
                    color = palette.accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable(onClick = onHowTo),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = onClear) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun LanguageRow(lang: String, p: Palette, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = Prefs.LANGUAGES.firstOrNull { it.first == lang }?.second ?: lang

    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = true }
                .padding(horizontal = OneUI.MARGIN, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Title("Translate to", p)
                Spacer(Modifier.height(4.dp))
                Sub("Source language is detected automatically", p)
            }
            Text(name, color = p.accent, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Prefs.LANGUAGES.forEach { (code, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onPick(code); open = false },
                )
            }
        }
    }
}

@Composable
private fun ModelRow(state: Translator.Model, p: Palette) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = OneUI.MARGIN, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Title("Language model", p)
            Spacer(Modifier.height(4.dp))
            Sub(
                when (state) {
                    Translator.Model.READY -> "On device. Translation works offline."
                    Translator.Model.DOWNLOADING -> "Downloading once, about 30 MB."
                    Translator.Model.FAILED -> Translator.lastError ?: "Download failed."
                    Translator.Model.IDLE -> "Not downloaded yet."
                },
                p,
            )
        }
        if (state == Translator.Model.DOWNLOADING) {
            CircularProgressIndicator(Modifier.size(22.dp), color = p.accent, strokeWidth = 2.dp)
        } else {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        when (state) {
                            Translator.Model.READY -> Color(0xFF14C46A)
                            Translator.Model.FAILED -> Color(0xFFFF5B5B)
                            else -> p.sub
                        },
                    ),
            )
        }
    }
}

@Composable
private fun ThemeRow(theme: String, p: Palette, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val options = listOf(
        Prefs.THEME_DARK to "Dark",
        Prefs.THEME_LIGHT to "Light",
    )
    val label = options.firstOrNull { it.first == theme }?.second ?: theme

    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = true }
                .padding(horizontal = OneUI.MARGIN, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Title("Overlay colours", p)
                Spacer(Modifier.height(4.dp))
                Sub("Used everywhere except where a reel label matches its background", p)
            }
            Text(label, color = p.accent, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { onPick(value); open = false },
                )
            }
        }
    }
}

@Composable
private fun SliderRow(
    title: String,
    subtitle: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    readout: String,
    p: Palette,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = OneUI.MARGIN, vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Title(title, p)
                Spacer(Modifier.height(4.dp))
                Sub(subtitle, p)
            }
            Text(readout, color = p.accent, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent),
        )
    }
}

// --- helpers ------------------------------------------------------------------

private fun isServiceEnabled(ctx: Context): Boolean {
    val flat = Settings.Secure.getString(
        ctx.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return flat.split(':').any { it.equals("${ctx.packageName}/${TransService::class.java.name}", true) }
}

