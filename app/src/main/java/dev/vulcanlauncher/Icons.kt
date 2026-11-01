package dev.vulcanlauncher

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.net.Uri
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

val SHAPES = listOf("system", "circle", "square", "rounded", "squircle", "teardrop", "pebble", "flower", "vessel", "tapered",
    "crop", "pentagon", "hexagon", "heptagon", "octagon")
val SHAPE_NAMES = listOf(
    R.string.shape_system, R.string.shape_circle, R.string.shape_square, R.string.shape_rounded,
    R.string.shape_squircle, R.string.shape_teardrop, R.string.shape_pebble, R.string.shape_flower,
    R.string.shape_vessel, R.string.shape_tapered, R.string.shape_crop, R.string.shape_pentagon,
    R.string.shape_hexagon, R.string.shape_heptagon, R.string.shape_octagon,
)

/** Outline of [shape] as points in the unit square, or null for "system" (keep the OS mask). */
fun shapeOutline(shape: String): List<Pair<Float, Float>>? = when (shape) {
    "circle" -> roundedSquare(0.5f, 0.5f, 0.5f, 0.5f)
    "square" -> roundedSquare(0f, 0f, 0f, 0f)
    "rounded" -> roundedSquare(0.2f, 0.2f, 0.2f, 0.2f)
    "teardrop" -> roundedSquare(0.5f, 0.5f, 0f, 0.5f)
    "tapered" -> roundedSquare(0.12f, 0.12f, 0.4f, 0.4f)
    "crop" -> listOf(0f to 0f, 0.75f to 0f, 1f to 0.25f, 1f to 1f, 0f to 1f)
    "squircle" -> superellipse(4.0, 4.0)
    "vessel" -> superellipse(2.0, 5.0)
    "pebble" -> polar { 0.92f + 0.08f * cos(2 * it - 0.7f) }
    "flower" -> polar { 0.92f + 0.08f * cos(8 * it) }
    "pentagon" -> polygon(5)
    "hexagon" -> polygon(6)
    "heptagon" -> polygon(7)
    "octagon" -> polygon(8)
    else -> null
}

/** Corner radii clockwise from top-left, as a fraction of the side. */
private fun roundedSquare(tl: Float, tr: Float, br: Float, bl: Float): List<Pair<Float, Float>> {
    val corners = listOf(Triple(tl, tl, tl), Triple(tr, 1 - tr, tr), Triple(br, 1 - br, 1 - br), Triple(bl, bl, 1 - bl))
    return corners.flatMapIndexed { i, (r, cx, cy) ->
        if (r == 0f) listOf(listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f)[i])
        else (0..16).map { s ->
            val a = PI + i * PI / 2 + s * PI / 2 / 16 // top-left arc starts pointing left
            (cx + r * cos(a)).toFloat() to (cy + r * sin(a)).toFloat()
        }
    }
}

/** Superellipse with exponent [top] for the upper half and [bottom] for the lower half. */
private fun superellipse(top: Double, bottom: Double) = (0 until 64).map { s ->
    val a = 2 * PI * s / 64
    val c = cos(a); val si = sin(a)
    val n = if (si < 0) top else bottom
    (0.5 + 0.5 * sign(c) * abs(c).pow(2 / n)).toFloat() to (0.5 + 0.5 * sign(si) * abs(si).pow(2 / n)).toFloat()
}

/** Shape from a radius function (1 = touches the edge) of the angle. */
private fun polar(r: (Float) -> Float) = (0 until 96).map { s ->
    val a = (2 * PI * s / 96).toFloat()
    0.5f + 0.5f * r(a) * cos(a) to 0.5f + 0.5f * r(a) * sin(a)
}

private fun polygon(n: Int) = (0 until n).map { k ->
    val a = -PI / 2 + 2 * PI * k / n
    (0.5 + 0.5 * cos(a)).toFloat() to (0.5 + 0.5 * sin(a)).toFloat()
}

fun outlinePath(outline: List<Pair<Float, Float>>, b: Rect) = Path().apply {
    outline.forEachIndexed { i, (x, y) ->
        val px = b.left + x * b.width()
        val py = b.top + y * b.height()
        if (i == 0) moveTo(px, py) else lineTo(px, py)
    }
    close()
}

/** Visible part of an icon box that legacy icons are scaled to fill when normalizing. */
const val NORMAL_FILL = 0.84f

/**
 * Size of the visible content of an [n]×[n] alpha map (row-major) as a fraction of the box: the larger of its width
 * and height. Null when the icon is fully transparent.
 */
fun visibleExtent(alpha: List<Int>, n: Int): Float? {
    var l = n; var t = n; var r = -1; var b = -1
    for (y in 0 until n) for (x in 0 until n) if (alpha[y * n + x] > 24) {
        if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
    }
    if (r < 0) return null
    return maxOf(r - l + 1, b - t + 1) / n.toFloat()
}

/** "ComponentInfo{pkg/cls}" from an icon pack's appfilter.xml → "pkg/cls", expanding a relative ".cls". */
fun parseComponent(s: String): String? {
    val inner = s.substringAfter("{", "").substringBefore("}", "")
    val pkg = inner.substringBefore("/", "")
    val cls = inner.substringAfter("/", "")
    if (pkg.isEmpty() || cls.isEmpty()) return null
    return "$pkg/" + if (cls.startsWith(".")) pkg + cls else cls
}

/** Draws an adaptive icon's layers clipped to [outline] instead of the system mask. */
class ShapedIcon(private val icon: AdaptiveIconDrawable, private val outline: List<Pair<Float, Float>>) : Drawable() {
    private var path = Path()

    override fun onBoundsChange(b: Rect) {
        path = outlinePath(outline, b)
        // Adaptive layers are 108dp with a 72dp visible window, so they extend 25% past each edge.
        val layer = Rect(b).apply { inset(-b.width() / 4, -b.height() / 4) }
        icon.background?.bounds = layer
        icon.foreground?.bounds = layer
    }

    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.clipPath(path)
        icon.background?.draw(canvas)
        icon.foreground?.draw(canvas)
        canvas.restore()
    }

    override fun getIntrinsicWidth() = icon.intrinsicWidth
    override fun getIntrinsicHeight() = icon.intrinsicHeight
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Required override") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Installed icon packs (the two common theme intent actions) as package → label. */
fun iconPacks(context: Context): List<Pair<String, String>> {
    val pm = context.packageManager
    return listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME")
        .flatMap { pm.queryIntentActivities(Intent(it), 0) }
        .distinctBy { it.activityInfo.packageName }
        .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        .sortedWith(compareBy(labelOrder()) { it.second })
}

/** An icon pack's appfilter: component → drawable name, plus the images it uses to theme apps it doesn't list. */
class IconPack(val pkg: String, val res: Resources, val map: Map<String, String>,
               val back: List<String>, val mask: String?, val upon: String?, val scale: Float) {
    fun drawable(name: String): Drawable? {
        val id = res.getIdentifier(name, "drawable", pkg)
        return if (id == 0) null else runCatching { res.getDrawable(id, null) }.getOrNull()
    }
    /**
     * Every icon for the "choose icon" picker: the pack's drawable.xml catalogue, all its categories, plus whatever
     * appfilter maps. Icons the pack offers but maps to no app are only in the catalogue.
     */
    val names by lazy {
        val listed = runCatching {
            val p = packXml(res, pkg, "drawable")
            buildList { while (p.next() != XmlPullParser.END_DOCUMENT) if (p.eventType == XmlPullParser.START_TAG && p.name == "item") p.getAttributeValue(null, "drawable")?.let(::add) }
        }.getOrDefault(emptyList())
        (map.values + listed).distinct().sorted()
    }
}

/** An icon pack's res/xml/[name].xml, or assets/[name].xml for packs that ship it there. */
private fun packXml(res: Resources, pkg: String, name: String): XmlPullParser {
    val id = res.getIdentifier(name, "xml", pkg)
    return if (id != 0) res.getXml(id)
    else XmlPullParserFactory.newInstance().newPullParser().apply { setInput(res.assets.open("$name.xml"), null) }
}

fun loadIconPack(context: Context, pkg: String): IconPack? = runCatching {
    val res = context.packageManager.getResourcesForApplication(pkg)
    val parser = packXml(res, pkg, "appfilter")
    val map = HashMap<String, String>()
    val back = ArrayList<String>()
    var mask: String? = null
    var upon: String? = null
    var scale = 1f
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
        if (parser.eventType != XmlPullParser.START_TAG) continue
        fun attrs() = (0 until parser.attributeCount).map { parser.getAttributeValue(it) }
        when (parser.name) {
            "item" -> {
                val c = parseComponent(parser.getAttributeValue(null, "component") ?: continue) ?: continue
                map.putIfAbsent(c, parser.getAttributeValue(null, "drawable") ?: continue)
            }
            "iconback" -> back += attrs()
            "iconmask" -> mask = attrs().firstOrNull()
            "iconupon" -> upon = attrs().firstOrNull()
            "scale" -> scale = parser.getAttributeValue(null, "factor")?.toFloatOrNull() ?: 1f
        }
    }
    IconPack(pkg, res, map, back, mask, upon, scale)
}.getOrNull()

/** App icons with the chosen icon pack, shape and legacy-icon options applied. Built once per activity. */
class Icons(private val context: Context, private val looks: Looks) {
    private val outline = shapeOutline(looks.str("shape"))
    val pack: IconPack? = looks.iconPack.takeIf { it.isNotEmpty() }?.let { loadIconPack(context, it) }
    private val size = (96 * context.resources.displayMetrics.density).toInt()

    /** [base] is the app's own icon; [custom] an "pack:<pkg>:<name>" or "uri:<uri>" override. */
    fun get(pkg: String, cls: String, custom: String, base: () -> Drawable): Drawable {
        custom(custom)?.let { return it }
        val themed = pack?.map?.get("$pkg/$cls")?.let { pack.drawable(it) }
        var icon = themed ?: base()
        if (themed == null && pack != null && looks.on("autogen") && pack.back.isNotEmpty()) autogen(icon, pkg)?.let { return it }
        val reshape = looks.str("reshape")
        if (icon !is AdaptiveIconDrawable && (reshape == "always" || reshape == "unthemed" && themed == null)) {
            icon = AdaptiveIconDrawable(ColorDrawable(android.graphics.Color.WHITE), InsetDrawable(icon, 0.28f))
        } else if (icon !is AdaptiveIconDrawable && looks.on("normalize")) {
            icon = normalized(icon)
        }
        return if (outline != null && icon is AdaptiveIconDrawable) ShapedIcon(icon, outline) else icon
    }

    /** Scales a legacy icon so its visible (non-transparent) part fills [NORMAL_FILL] of the icon box. */
    private fun normalized(icon: Drawable): Drawable {
        val n = 64
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, n, n); icon.draw(Canvas(bmp))
        val px = IntArray(n * n).also { bmp.getPixels(it, 0, n, 0, 0, n, n) }
        val extent = visibleExtent(px.map { it ushr 24 }, n) ?: return icon
        val inset = ((1 - NORMAL_FILL / extent) / 2).coerceIn(-0.25f, 0.4f)
        return InsetDrawable(icon, inset)
    }

    fun custom(custom: String): Drawable? = when {
        custom.startsWith("pack:") -> {
            val (pkg, name) = custom.removePrefix("pack:").split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            (if (pkg == pack?.pkg) pack else loadIconPack(context, pkg))?.drawable(name)
        }
        custom.startsWith("uri:") -> runCatching {
            context.contentResolver.openInputStream(Uri.parse(custom.removePrefix("uri:")))!!.use {
                BitmapDrawable(context.resources, android.graphics.BitmapFactory.decodeStream(it))
            }
        }.getOrNull()
        else -> null
    }

    /** ADW-style theming for apps the pack doesn't list: back plate, masked and scaled icon, overlay. */
    private fun autogen(icon: Drawable, pkg: String): Drawable? {
        val p = pack ?: return null
        val back = p.drawable(p.back[abs(pkg.hashCode()) % p.back.size]) ?: return null
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        back.setBounds(0, 0, size, size); back.draw(c)
        val inner = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val ic = Canvas(inner)
        val pad = ((1 - p.scale) * size / 2).toInt()
        icon.setBounds(pad, pad, size - pad, size - pad); icon.draw(ic)
        p.mask?.let { p.drawable(it) }?.let { m ->
            m.setBounds(0, 0, size, size)
            val maskBmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            m.draw(Canvas(maskBmp))
            ic.drawBitmap(maskBmp, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        }
        c.drawBitmap(inner, 0f, 0f, null)
        p.upon?.let { p.drawable(it) }?.let { it.setBounds(0, 0, size, size); it.draw(c) }
        return BitmapDrawable(context.resources, bmp)
    }
}
