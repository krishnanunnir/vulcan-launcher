package dev.vulcanlauncher

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

// Material 3 built from platform views: a colour scheme (the wallpaper's colours on Android 12+, otherwise
// generated from a seed colour) and the few components the launcher uses.

/** Linear mix of two ARGB colours; [f] 0 gives [a], 1 gives [b]. */
fun blend(a: Int, b: Int, f: Float): Int {
    fun ch(s: Int) = ((a shr s and 255) * (1 - f) + (b shr s and 255) * f).roundToInt() shl s
    return ch(24) or ch(16) or ch(8) or ch(0)
}

private fun hsl(h: Float, sat: Float, l: Float): Int {
    val c = (1 - abs(2 * l - 1)) * sat
    val x = c * (1 - abs((h / 60) % 2 - 1))
    val m = l - c / 2
    val (r, g, b) = when ((h / 60).toInt()) {
        0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
    }
    fun ch(v: Float) = ((v + m) * 255).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
}

/** Relative luminance (sRGB, 0–1). */
fun luminance(c: Int): Double {
    fun ch(v: Int) = (v / 255.0).let { if (it <= 0.04045) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
    return 0.2126 * ch(c shr 16 and 255) + 0.7152 * ch(c shr 8 and 255) + 0.0722 * ch(c and 255)
}

/**
 * [seed]'s hue at M3 tone [tone] (CIE L*: 0 black, 100 white), with its saturation scaled by [satScale] and capped
 * at [satMax]. Builds tonal palettes when the system doesn't provide them.
 */
// ponytail: HSL hue and saturation searched to the right L*, not M3's HCT; chroma differs a little, contrast doesn't.
fun tone(seed: Int, tone: Int, satScale: Float, satMax: Float): Int {
    val r = (seed shr 16 and 255) / 255f
    val g = (seed shr 8 and 255) / 255f
    val b = (seed and 255) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    val l0 = (max + min) / 2
    val s = if (d == 0f || l0 == 0f || l0 == 1f) 0f else d / (1 - abs(2 * l0 - 1))
    var h = when {
        d == 0f -> 0f
        max == r -> 60 * (((g - b) / d) % 6)
        max == g -> 60 * ((b - r) / d + 2)
        else -> 60 * ((r - g) / d + 4)
    }
    if (h < 0) h += 360
    val sat = minOf(s * satScale, satMax)
    if (tone <= 0) return 0xFF000000.toInt()
    if (tone >= 100) return 0xFFFFFFFF.toInt()
    val t = tone.toDouble()
    val y = if (t > 8) Math.pow((t + 16) / 116, 3.0) else t / 903.3
    // Luminance rises with HSL lightness for a fixed hue and saturation, so bisect for the target.
    var lo = 0f
    var hi = 1f
    repeat(24) { val mid = (lo + hi) / 2; if (luminance(hsl(h, sat, mid)) < y) lo = mid else hi = mid }
    return hsl(h, sat, (lo + hi) / 2)
}

/**
 * M3 colour roles. Each ramp maps a tone (0–100) to a colour: primary, secondary, neutral, neutral variant.
 * [housing] is the Appliance look: warm-grey housing and wells from [n1], black legends where M3 puts the accent,
 * and the accent kept for [signal], the one live thing on screen.
 * [ov] holds the user's colours by [COLOR_ROLES] key; they replace the scheme's.
 */
class Palette(val dark: Boolean, a1: (Int) -> Int, a2: (Int) -> Int, n1: (Int) -> Int, n2: (Int) -> Int, val housing: Boolean = false,
              val ov: Map<String, Int> = emptyMap()) {
    val ramps = listOf(a1, a2, n1, n2)
    private fun t(light: Int, dark: Int) = if (this.dark) dark else light
    /** M3's tone pair from [ramp], or the housing's literal colours ([hl] light, [hd] dark). */
    private fun r(ramp: (Int) -> Int, light: Int, dark: Int, hl: Long, hd: Long) = if (housing) t(hl.toInt(), hd.toInt()) else ramp(t(light, dark))
    /** The housing's primary is its legends, the text colour; a custom accent only replaces M3's. */
    private val accent = if (housing) ov["onSurface"] else ov["accent"]
    val primary = accent ?: r(a1, 40, 80, 0xFF2A2A28, 0xFFE9E7E2)
    val onPrimary = accent?.let(::onColor) ?: r(a1, 100, 20, 0xFFE9E7E2, 0xFF2A2A28)
    val primaryContainer = r(a1, 90, 30, 0xFFD1CEC7, 0xFF3E3E3A)
    val onPrimaryContainer = r(a1, 10, 90, 0xFF2A2A28, 0xFFE9E7E2)
    val secondaryContainer = r(a2, 90, 30, 0xFFD1CEC7, 0xFF3E3E3A)
    val onSecondaryContainer = r(a2, 10, 90, 0xFF2A2A28, 0xFFE9E7E2)
    val onSurface = ov["onSurface"] ?: r(n1, 10, 90, 0xFF2A2A28, 0xFFE9E7E2)
    val surface = ov["surface"] ?: r(n1, 98, 6, 0xFFE9E7E2, 0xFF2A2A28)
    /** A container tone; under a custom background, that background stepped toward the text by [f]. */
    private fun step(f: Float, tone: Int) = ov["surface"]?.let { blend(it, onSurface, f) } ?: tone
    val surfaceLow = step(0.03f, r(n1, 96, 10, 0xFFE4E2DC, 0xFF2E2E2B))
    val surfaceContainer = step(0.05f, r(n1, 94, 12, 0xFFDEDBD5, 0xFF33332F))
    val surfaceHigh = step(0.08f, r(n1, 92, 17, 0xFFD8D5CE, 0xFF383834))
    val surfaceHighest = step(0.11f, r(n1, 90, 22, 0xFFD1CEC7, 0xFF3E3E3A))
    /** A raised key or card on the housing: menus and folder windows. */
    val raised = ov["raised"] ?: r(n1, 97, 22, 0xFFF3F2EE, 0xFF363632)
    /** A recessed well (search slot, dock channel): always darker than the housing around it. */
    val well = ov["well"] ?: r(n1, 90, 4, 0xFFDEDBD5, 0xFF222220)
    val onSurfaceVariant = ov["onSurfaceVariant"] ?: r(n2, 30, 80, 0xFF595752, 0xFFA9A69F)
    val outline = r(n2, 50, 60, 0xFF8C8A84, 0xFF8C8A84)
    val outlineVariant = ov["outlineVariant"] ?: r(n2, 80, 30, 0xFFC9C6BF, 0xFF4A4945)
    val inverseSurface = n1(t(20, 90))
    val inverseOnSurface = n1(t(95, 20))
    val inversePrimary = a1(t(80, 40))
    val errorContainer = t(0xFFF9DEDC.toInt(), 0xFF8C1D18.toInt())
    val onErrorContainer = t(0xFF410E0B.toInt(), 0xFFF9DEDC.toInt())
    /** The one live thing: current page, active index letter, focused search. Never text on the housing. */
    val signal = ov["accent"] ?: if (housing) SIGNAL else primary
    /** The signal drawn on the housing itself (index dot, search glyph): darker in light so it holds 3:1 there. */
    val signalInk = ov["accent"] ?: if (housing) t(0xFFB85410.toInt(), SIGNAL) else primary
    /** A switch that's on. */
    val on = if (housing) t(0xFF4E8F3A.toInt(), 0xFF7DB862.toInt()) else primary
}

/** Black or white, whichever reads better on [bg]. */
fun onColor(bg: Int) = if (contrast(bg, 0xFF000000.toInt()) >= contrast(bg, Color.WHITE)) 0xFF000000.toInt() else Color.WHITE

/** WCAG contrast ratio; M3 pairs text roles with their containers at 4.5:1 or more. */
fun contrast(a: Int, b: Int) = (maxOf(luminance(a), luminance(b)) + 0.05) / (minOf(luminance(a), luminance(b)) + 0.05)

/** The colour roles a user can set, by [Palette.ov] key, with their names. Stored in looks as "c_<key>" = "#RRGGBB". */
val COLOR_ROLES = listOf("accent" to R.string.role_accent, "surface" to R.string.role_surface, "onSurface" to R.string.role_text,
    "onSurfaceVariant" to R.string.role_text2, "raised" to R.string.role_raised, "well" to R.string.role_well,
    "outlineVariant" to R.string.role_divider)

/** This palette with [more] of the user's colours laid over it. */
fun Palette.with(more: Map<String, Int>) = Palette(dark, ramps[0], ramps[1], ramps[2], ramps[3], housing, ov + more)

/** The worst contrast of text on the backgrounds it's drawn on, for showing; [lowContrast] decides what falls short. */
fun Palette.textContrast() = listOf(onSurface, onSurfaceVariant).minOf { fg -> listOf(surface, surfaceHigh, raised, well).minOf { contrast(fg, it) } }

fun Palette.role(key: String) = when (key) {
    "accent" -> signal; "surface" -> surface; "onSurface" -> onSurface; "onSurfaceVariant" -> onSurfaceVariant
    "raised" -> raised; "well" -> well; else -> outlineVariant
}

/**
 * The worst contrast that falls short, as (ratio, minimum), or null when all read well: text on the backgrounds it's
 * drawn on needs 4.5:1, the accent (as text, buttons and glyphs) 3:1.
 */
fun Palette.lowContrast(): Pair<Double, Double>? {
    val bgs = listOf(surface, surfaceHigh, raised, well)
    return (listOf(onSurface, onSurfaceVariant).flatMap { fg -> bgs.map { contrast(fg, it) to 4.5 } } +
        listOf(primary, signalInk).flatMap { fg -> listOf(surface, surfaceHigh).map { contrast(fg, it) to 3.0 } })
        .filter { it.first < it.second }.minByOrNull { it.first / it.second }
}

/** Appliance signal orange. */
const val SIGNAL = 0xFFE8721C.toInt()

/** The Appliance look: warm-grey housing with black legends and a signal orange. */
fun appliancePalette(dark: Boolean, ov: Map<String, Int> = emptyMap()) = 0xFF8C8A84.toInt().let { grey ->
    Palette(dark, { tone(SIGNAL, it, 1f, 0.85f) }, { tone(grey, it, 1.5f, 0.08f) }, { tone(grey, it, 1.5f, 0.08f) },
        { tone(grey, it, 1.5f, 0.1f) }, housing = true, ov = ov)
}

fun seedPalette(seed: Int, dark: Boolean, ov: Map<String, Int> = emptyMap()) = Palette(dark,
    { tone(seed, it, 1f, 0.85f) }, { tone(seed, it, 0.4f, 0.3f) }, { tone(seed, it, 0.15f, 0.05f) }, { tone(seed, it, 0.3f, 0.12f) }, ov = ov)

/** Android 12+ wallpaper ("Material You") palette. Shade N of a system ramp is tone 100 − N/10; tones in between are mixed. */
@TargetApi(31)
@SuppressLint("DiscouragedApi")
private fun systemPalette(c: Context, dark: Boolean, ov: Map<String, Int>): Palette {
    val shades = intArrayOf(0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000)
    fun ramp(name: String): (Int) -> Int {
        val colors = shades.map { c.getColor(c.resources.getIdentifier("system_${name}_$it", "color", "android")) }
        return { t ->
            val shade = (100 - t) * 10f
            val i = shades.indexOfLast { it <= shade }.coerceIn(0, shades.size - 2)
            blend(colors[i], colors[i + 1], ((shade - shades[i]) / (shades[i + 1] - shades[i])).coerceIn(0f, 1f))
        }
    }
    return Palette(dark, ramp("accent1"), ramp("accent2"), ramp("neutral1"), ramp("neutral2"), ov = ov)
}

/**
 * AMOLED Black: always dark, the background pure black and cards a few tones above it, accents from [base]
 * (the wallpaper's colours). Dark surfaces sit at tones 4–22; shifting the neutral ramp down 6 puts the surface at 0.
 */
fun amoledPalette(base: Palette, neutral: (Int) -> Int = { tone(0xFF808080.toInt(), it, 0f, 0f) }) =
    Palette(true, base.ramps[0], base.ramps[1], { neutral((it - 6).coerceAtLeast(0)) }, base.ramps[3], ov = base.ov)

/**
 * "appliance" is the default look; "system" means the wallpaper's colours: the system palette on Android 12+, else a
 * seed taken from the wallpaper. "amoled" is the wallpaper's accents on pure black. [ov] are the user's colours on top.
 */
fun palette(c: Context, dark: Boolean, accent: String, ov: Map<String, Int> = emptyMap()): Palette {
    if (accent == "appliance") return appliancePalette(dark, ov)
    if (accent == "amoled") return amoledPalette(palette(c, true, "system", ov))
    if (accent == "system" && Build.VERSION.SDK_INT >= 31) return systemPalette(c, dark, ov)
    val seed = runCatching { Color.parseColor(accent) }.getOrNull()
        ?: (if (Build.VERSION.SDK_INT >= 27) runCatching {
            WallpaperManager.getInstance(c).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb()
        }.getOrNull() else null)
        ?: 0xFF6750A4.toInt()
    return seedPalette(seed, dark, ov)
}

// ---- Components ----

fun MainActivity.rounded(color: Int, radius: Float, stroke: Int = 0, strokeWidth: Float = dp) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (stroke != 0) setStroke(strokeWidth.roundToInt(), stroke)
}

/** M3 state layer: a ripple in the content colour, over [bg] and clipped to [radius]. */
fun MainActivity.pressable(bg: Drawable?, radius: Float = 0f, content: Int = m3.onSurface) =
    RippleDrawable(ColorStateList.valueOf(content and 0xFFFFFF or 0x1F000000), bg, rounded(Color.WHITE, radius))

fun MainActivity.text(s: CharSequence, sp: Float, color: Int = m3.onSurface, medium: Boolean = false) = TextView(this).apply {
    text = s
    textSize = sp
    setTextColor(color)
    if (medium) typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
}

fun MainActivity.symbol(name: String, color: Int = m3.onSurfaceVariant, size: Float = 24f) = Symbol(name, color, (size * dp).roundToInt())

/** 48 dp round icon button; [label] is what TalkBack says. */
fun MainActivity.iconButton(name: String, label: String, color: Int = m3.onSurfaceVariant, onClick: (View) -> Unit) = ImageView(this).apply {
    setImageDrawable(symbol(name, color))
    contentDescription = label
    scaleType = ImageView.ScaleType.CENTER
    background = pressable(null, 24 * dp)
    layoutParams = LinearLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt())
    setOnClickListener(onClick)
}

/** Filled tonal button. */
fun MainActivity.tonalButton(label: String, onClick: () -> Unit) = text(label, 14f, m3.onSecondaryContainer, medium = true).apply {
    gravity = Gravity.CENTER
    minHeight = (40 * dp).toInt()
    setPadding((24 * dp).toInt(), 0, (24 * dp).toInt(), 0)
    background = pressable(rounded(m3.secondaryContainer, 20 * dp), 20 * dp, m3.onSecondaryContainer)
    setOnClickListener { onClick() }
}

/**
 * M3 list item: leading icon, headline, optional supporting text and trailing view. [dense] is the 48 dp menu item.
 * [tonalIcon] puts the icon in a primary-container circle, as on a settings home page.
 */
fun MainActivity.listItem(icon: Drawable?, title: CharSequence, summary: CharSequence? = null, trailing: View? = null,
                          dense: Boolean = false, tonalIcon: Boolean = false, appIcon: Boolean = false, onClick: ((View) -> Unit)?) = LinearLayout(this).apply {
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = ((if (dense) 48 else if (summary == null) 56 else 72) * dp).toInt()
    val h = ((if (dense) 12 else 16) * dp).toInt()
    setPadding(h, (8 * dp).toInt(), if (dense) h else (24 * dp).toInt(), (8 * dp).toInt())
    if (onClick != null) { background = pressable(null); setOnClickListener(onClick) }
    if (icon != null) addView(ImageView(context).apply {
        setImageDrawable(icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        if (tonalIcon) {
            scaleType = ImageView.ScaleType.CENTER
            background = if (m3.housing) well(20 * dp) else GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(m3.primaryContainer) }
        }
    }, LinearLayout.LayoutParams(((if (tonalIcon || appIcon) 40 else 24) * dp).toInt(), ((if (tonalIcon || appIcon) 40 else 24) * dp).toInt()).apply {
        marginEnd = ((if (dense) 12 else 16) * dp).toInt()
    })
    addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        // Short titles, explanations go in the summary; two lines so longer ones (and translations) aren't cut.
        addView(text(title, if (dense) 14f else 16f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        if (!summary.isNullOrEmpty()) addView(text(summary, 14f, m3.onSurfaceVariant).apply { maxLines = 10; ellipsize = android.text.TextUtils.TruncateAt.END })
    }, LinearLayout.LayoutParams(0, -2, 1f))
    trailing?.let { addView(it, ((it.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(-2, -2)).apply { marginStart = (16 * dp).toInt() }) }
}

/** M3 outlined text field: 56 dp tall, a 4 dp corner, the outline turning primary while focused. */
fun MainActivity.field(value: String, hintText: String) = android.widget.EditText(this).apply {
    setText(value)
    hint = hintText
    setSingleLine()
    textSize = 16f
    setTextColor(m3.onSurface)
    setHintTextColor(m3.onSurfaceVariant)
    minHeight = (56 * dp).toInt()
    setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
    fun outline(f: Boolean) { background = rounded(0, 4 * dp, if (f) m3.primary else m3.outline, if (f) 2 * dp else dp) }
    outline(false)
    setOnFocusChangeListener { _, f -> outline(f) }
}

/**
 * An M3 dialog list: a row per entry with its leading icon ([appIcon]: 40 dp app icons) and optional supporting text
 * ([summaries]). Picking one closes it.
 */
fun MainActivity.pickList(title: CharSequence, rows: List<Pair<Drawable?, CharSequence>>, appIcon: Boolean = false,
                          summaries: List<CharSequence>? = null, onPick: (Int) -> Unit) {
    val adapter = object : android.widget.BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: android.view.ViewGroup) =
            listItem(rows[p].first, rows[p].second, summaries?.get(p), appIcon = appIcon, dense = !appIcon, onClick = null).apply { setPadding((24 * dp).toInt(), paddingTop, (24 * dp).toInt(), paddingBottom) }
    }
    dialog().setTitle(title).setAdapter(adapter) { _, i -> onPick(i) }.setNegativeButton(R.string.cancel, null).show()
}

fun MainActivity.divider() = View(this).apply {
    setBackgroundColor(m3.outlineVariant)
    layoutParams = LinearLayout.LayoutParams(-1, maxOf(1, dp.toInt())).apply { setMargins(0, (8 * dp).toInt(), 0, (8 * dp).toInt()) }
}

/** M3 section header in a list. */
fun MainActivity.sectionHeader(title: String) = (if (m3.housing) legend(title, 12f) else text(title, 14f, m3.primary, medium = true)).apply {
    setPadding((16 * dp).toInt(), (24 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
}

/** A printed legend, Appliance style: small tracked capitals in the secondary ink. */
fun MainActivity.legend(s: String, sp: Float = 11f, color: Int = m3.onSurfaceVariant) = text(s.uppercase(), sp, color, medium = true).apply {
    letterSpacing = 0.1f
}

/**
 * A machined housing face in [color]: a 1 dp rim that catches light along the top and falls into shade along the
 * bottom, the way a moulded edge does.
 */
fun MainActivity.housingFace(color: Int, radius: Float): Drawable = android.graphics.drawable.LayerDrawable(arrayOf(
    GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
        blend(color, Color.WHITE, if (m3.dark) 0.14f else 0.6f), color, blend(color, Color.BLACK, if (m3.dark) 0.35f else 0.12f),
    )).apply { cornerRadius = radius },
    rounded(color, maxOf(0f, radius - dp)),
)).apply { val i = dp.roundToInt(); setLayerInset(1, i, i, i, i) }

/** Raised housing (dock, menus, folder windows): a machined face and a soft shadow under it. */
fun MainActivity.housing(v: View, radius: Float, color: Int = m3.raised) {
    v.background = housingFace(color, radius)
    v.elevation = 8 * dp
    v.outlineProvider = object : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, view.width, view.height, minOf(radius, view.height / 2f))
    }
}

/**
 * A recessed well: darker than the housing, in shade under its top edge and catching light on its lower lip,
 * as if pressed into the face around it.
 */
fun MainActivity.well(radius: Float, color: Int = m3.well): Drawable = android.graphics.drawable.LayerDrawable(arrayOf(
    GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
        blend(color, Color.BLACK, if (m3.dark) 0.45f else 0.18f), color, blend(color, Color.WHITE, if (m3.dark) 0.08f else 0.5f),
    )).apply { cornerRadius = radius },
    rounded(color, maxOf(0f, radius - dp)),
    GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(if (m3.dark) 0x59000000 else 0x24000000, 0, 0)).apply { cornerRadius = radius },
)).apply { val i = dp.roundToInt(); setLayerInset(1, i, i, i, i); setLayerInset(2, i, i, i, i) }

/** Android's cross-window blur: Android 12+, the Blur setting on, and the system allowing it (not in battery saver). */
val MainActivity.windowBlur get() = Build.VERSION.SDK_INT >= 31 && looks.on("blur") && windowManager.isCrossWindowBlurEnabled

/** True when panels get real frosted glass, from either Android's window blur or Samsung's view blur. */
val MainActivity.glassBlur get() = windowBlur || looks.on("blur") && SEM_BLUR != null

/** Samsung's view blur: SemBlurInfo.Builder(mode), its setters and build(), and View.semSetBlurInfo. */
private class SemBlur(val builder: java.lang.reflect.Constructor<*>, val mode: Int, val radius: java.lang.reflect.Method,
                      val color: java.lang.reflect.Method, val corner: java.lang.reflect.Method, val build: java.lang.reflect.Method,
                      val set: java.lang.reflect.Method)

// ponytail: One UI's private API through reflection (One UI turns Android's own cross-window blur off). If an update
// renames it, SEM_BLUR is null and panels fall back to the denser tint.
private val SEM_BLUR: SemBlur? by lazy {
    runCatching {
        val info = Class.forName("android.view.SemBlurInfo")
        val b = Class.forName("android.view.SemBlurInfo\$Builder")
        SemBlur(b.getConstructor(Int::class.javaPrimitiveType), info.getField("BLUR_MODE_WINDOW").getInt(null),
            b.getMethod("setRadius", Int::class.javaPrimitiveType), b.getMethod("setBackgroundColor", Int::class.javaPrimitiveType),
            b.getMethod("setBackgroundCornerRadius", Float::class.javaPrimitiveType), b.getMethod("build"),
            View::class.java.getMethod("semSetBlurInfo", info))
    }.getOrNull()
}

/**
 * Frosts this view on Samsung phones: what's behind it, wallpaper included, blurred by [radius] px under a [tint],
 * clipped to [corner]. [radius] 0 removes it. Does nothing elsewhere.
 */
fun View.semBlur(radius: Int, tint: Int, corner: Float) {
    val s = SEM_BLUR ?: return
    runCatching {
        s.set.invoke(this, if (radius <= 0) null else s.builder.newInstance(s.mode).let {
            s.radius.invoke(it, radius); s.color.invoke(it, tint); s.corner.invoke(it, corner); s.build.invoke(it)
        })
    }
}

/**
 * How far the home screen sits behind a panel: 0 at rest, 1 under an open panel. It sinks back slightly and blurs:
 * the icons through a RenderEffect, the wallpaper (drawn by the system behind this window) through window blur.
 */
fun MainActivity.setDepth(p: Float) {
    depth = p
    // Dark status bar icons while a light panel covers home.
    @Suppress("DEPRECATION") window.decorView.apply {
        // The housing drawer stops below the status bar, over a dimmed home: light icons stay.
        val light = p > 0.5f && !m3.dark && (this@setDepth.overlay != null || looks.str("drawerStyle") != "housing") || looks.on("darkStatusIcons")
        val f = if (light) systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        if (f != systemUiVisibility) systemUiVisibility = f
    }
    val s = 1 - 0.04f * p
    content.scaleX = s; content.scaleY = s
    // The big clock text would read through a translucent panel and double up with its own clock. Not while it's
    // being resized: the frame is around it.
    clocks.forEach { it.alpha = if (overlay is ResizeFrame) 1f else 1 - p }
    if (Build.VERSION.SDK_INT < 31) return
    val r = if (looks.on("blur")) 24 * dp * p else 0f
    content.setRenderEffect(if (r > 0.5f) android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP) else null)
    window.setBackgroundBlurRadius(if (windowBlur) (40 * dp * p).roundToInt() else 0)
}

/** Animates [setDepth] to 1 ([on]) or 0. */
fun MainActivity.blurHome(on: Boolean, animate: Boolean = false, duration: Long = 340) {
    depthAnim?.cancel()
    val to = if (on) 1f else 0f
    if (!animate) { setDepth(to); return }
    depthAnim = ValueAnimator.ofFloat(depth, to).apply {
        this.duration = duration
        interpolator = EASE
        addUpdateListener { setDepth(it.animatedValue as Float) }
        start()
    }
}

/** True when a single view (a menu card, the dock) can blur what's behind it: Samsung's view blur. */
val MainActivity.viewBlur get() = looks.on("blur") && SEM_BLUR != null

/**
 * The glass tint. [blurred] says whether what's behind is really blurred: then the tint is light, otherwise dense
 * so text stays readable over a sharp wallpaper.
 */
fun MainActivity.glassTint(blurred: Boolean): Int = when {
    m3.dark -> if (blurred) 0x8C141416 else 0xE6161618
    else -> if (blurred) 0xA6F5F5F7 else 0xEBF7F7F9
}.toInt()

/** Frosted glass for a card or menu: the glass tint, a hairline edge, and on Samsung a real blur of what's behind [v]. */
fun MainActivity.frost(v: View, radius: Float) {
    if (!looks.on("blur")) { housing(v, radius); return }
    // A raised glass: lighter than a panel's tint, so a menu stands out over the drawer too.
    val tint = when {
        m3.dark -> if (viewBlur) 0xB32C2C30 else 0xF02C2C30
        else -> if (viewBlur) 0xC7FFFFFF else 0xF5FFFFFF
    }.toInt()
    v.background = rounded(tint, radius, if (m3.dark) 0x24FFFFFF else 0x1A000000, dp / 2)
    // Samsung's blur draws instead of the background, so it carries the tint itself.
    if (viewBlur) v.semBlur((32 * dp).toInt(), tint, radius)
}

/** A light haptic tick (page settled, drawer committed, item dropped). */
fun View.tick() = performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)

/** A firmer confirmation (folder made, drop accepted). */
fun View.confirm() = performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.VIRTUAL_KEY)

/** M3 modal bottom sheet over a scrim, with a drag handle and an optional title. Tap the scrim to close. */
fun MainActivity.showSheet(title: String?, build: LinearLayout.() -> Unit) {
    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            // Housing, or glass over the blurred home screen (showOverlay blurs it). Same corner as the drawer sheet.
            setColor(if (looks.on("blur")) glassTint(glassBlur) else m3.surface)
            val r = (if (m3.housing) 22 else 28) * dp
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        isClickable = true
        setPadding(0, 0, 0, insets.bottom + (12 * dp).toInt())
        addView(View(context).apply { background = rounded(m3.onSurfaceVariant and 0x66FFFFFF, 2 * dp) },
            LinearLayout.LayoutParams((32 * dp).toInt(), (4 * dp).toInt()).apply { gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, (16 * dp).toInt(), 0, (12 * dp).toInt()) })
        if (title != null) addView(text(title, 22f).apply { setPadding((24 * dp).toInt(), 0, (24 * dp).toInt(), (12 * dp).toInt()) })
        build()
    }
    val scrim = FrameLayout(this).apply {
        setBackgroundColor(if (m3.housing) 0x66000000 else 0x52000000)
        setOnClickListener { closeOverlay() }
        addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(body) },
            FrameLayout.LayoutParams(minOf(root.width, (640 * dp).toInt()).takeIf { it > 0 } ?: -1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = insets.top + (48 * dp).toInt()
            })
    }
    showOverlay(scrim)
    body.translationY = root.height.toFloat()
    body.animate().translationY(0f).setDuration(300).setInterpolator(DecelerateInterpolator(2f)).start()
}

/** Dialogs: M3 surface, 28 dp corners and primary-coloured buttons. The scrim is the activity's (see MainActivity.dialog). */
fun MainActivity.styleDialog(d: AlertDialog) {
    d.window?.apply {
        setBackgroundDrawable(InsetDrawable(rounded(m3.surfaceHigh, 28 * dp), (24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt()))
    }
    for (b in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) d.getButton(b)?.apply {
        // An accent the user made unreadable here must not hide the way back (Reset, Cancel).
        setTextColor(if (contrast(m3.primary, m3.surfaceHigh) >= 3) m3.primary else m3.onSurface)
        isAllCaps = false
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
}

/** M3 switch: 52×32 dp track, thumb that grows when on. The row around it handles taps. */
@SuppressLint("ViewConstructor")
class Toggle(private val a: MainActivity, on: Boolean) : View(a) {
    private var pos = if (on) 1f else 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = RectF()
    var checked = on
        set(v) {
            field = v
            ValueAnimator.ofFloat(pos, if (v) 1f else 0f).apply {
                duration = 180
                addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            }.start()
        }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((52 * a.dp).toInt(), (32 * a.dp).toInt())

    override fun onDraw(canvas: Canvas) {
        val d = a.dp
        val p = a.m3
        val track = track.apply { set(d, d, width - d, height - d) }
        paint.style = Paint.Style.FILL
        paint.color = blend(p.surfaceHighest, p.on, pos)
        canvas.drawRoundRect(track, track.height() / 2, track.height() / 2, paint)
        if (pos < 1f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2 * d
            paint.color = p.outline
            paint.alpha = ((1 - pos) * 255).toInt()
            canvas.drawRoundRect(track, track.height() / 2, track.height() / 2, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = blend(p.outline, p.onPrimary, pos)
        canvas.drawCircle(16 * d + 20 * d * pos, height / 2f, (8 + 4 * pos) * d, paint)
    }
}
