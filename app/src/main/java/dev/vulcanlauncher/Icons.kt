package dev.vulcanlauncher

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
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

/** [shape] as a path filling [b]; "system" is the device's own icon mask. */
fun shapePath(shape: String, b: Rect): Path = shapeOutline(shape)?.let { outlinePath(it, b) }
    ?: Path(AdaptiveIconDrawable(ColorDrawable(0), ColorDrawable(0)).apply { bounds = b }.iconMask)

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

/**
 * How a legacy icon fills its shape: scaled so its visible part ([extent] of its box) just covers the shape, its edge
 * stretched out over whatever the shape shows past it ([extendEdge]), on [color]; [peel] clears a system plate off it
 * first.
 */
data class LegacyFill(val color: Int, val extent: Float, val peel: Boolean = false)

/** The circle as an [n]×[n] mask: [legacyFill]'s default shape, the one that crops an icon the most. */
fun circleMask(n: Int) = BooleanArray(n * n) { i -> Math.hypot(i % n - (n - 1) / 2.0, i / n - (n - 1) / 2.0) <= n / 2.0 }

/**
 * How to reshape a legacy icon, from its [n]×[n] ARGB pixels (row-major), to fill [shape] (an [n]×[n] mask of the
 * shape's visible area). It fills when the icon is itself an icon shape (a square, squircle or circle) that, scaled
 * to cover [shape], loses only background: nothing of its art (a letter, a figure) reaches what the shape cuts off.
 * Null for any other icon (a mascot, a cut-out logo, art to its edge, a wide banner): it goes on a plate instead.
 * As Nova 8.9.4 reshapes them, measured on 324 F-Droid icons (#92).
 */
fun legacyFill(px: IntArray, n: Int, shape: BooleanArray = circleMask(n)): LegacyFill? {
    val outer = solidEdge(px, n)?.let { (fill, extent) -> LegacyFill(fill, extent) }
    val fills = { a: IntArray, f: LegacyFill, solid: (Int) -> Boolean -> iconBody(a, n, solid) && keepsArt(a, n, f.extent, shape) }
    // A white plate with a solid icon inside: the system's (EMUI puts every unthemed icon on one, #92). Peel it, and
    // fill the shape on the inner icon's own edge colour, if that icon is an icon shape, not a mark on the plate.
    if (outer == null || (0..16 step 8).all { outer.color shr it and 0xFF >= 230 }) {
        val inner = px.copyOf().also { peelPlate(it, n) }
        val opaque = { a: IntArray -> a.count { it ushr 24 > 200 } }
        val extent = visibleExtent(px.map { it ushr 24 }, n)
        val peeled = solidEdge(inner, n, n / 32)?.takeIf { extent != null && it.second < extent * 0.9f && opaque(px) - opaque(inner) >= n * n / 4 }
            ?.let { (fill, e) -> LegacyFill(fill, e, peel = true) }
        // Judged as drawn, without the rim the plate blended into it (reshaped() peels with a grow too).
        val drawn = px.copyOf().also { peelPlate(it, n, grow = maxOf(1, n / 48)) }
        if (peeled != null && iconBody(inner, n) { it != 0 } && keepsArt(drawn, n, peeled.extent, shape)) return peeled
    }
    if (outer != null) return outer.takeIf { fills(px, it) { p -> p ushr 24 >= 250 } }
    // (An icon shape is opaque: a see-through one would show through its stretched edge, so it goes on a plate.)
    // No one edge colour (a gradient, a two-tone split, a photo to its edge): on the mean of its edge.
    val ring = edgeRing(px, n) ?: return null
    fun mean(shift: Int) = ring.sumOf { it shr shift and 0xFF } / ring.size
    val fill = LegacyFill(0xFF shl 24 or (mean(16) shl 16) or (mean(8) shl 8) or mean(0), visibleExtent(px.map { it ushr 24 }, n) ?: return null)
    return fill.takeIf { fills(px, it) { p -> p ushr 24 >= 250 } }
}

/**
 * The plate for a legacy icon that can't fill its shape: a pale tint of its main colour (the middle of its most
 * common hue, 46% into white), or white when it has little colour or a warm one (red, orange, brown), as Nova's
 * plates are.
 */
fun plateTint(px: IntArray, n: Int): Int {
    val opaque = px.filter { it ushr 24 > 200 }
    val coloured = opaque.filter { c -> (0..16 step 8).map { c shr it and 0xFF }.let { it.max() - it.min() >= 48 } }
    if (coloured.size < maxOf(opaque.size / 5, 1)) return android.graphics.Color.WHITE
    fun chroma(c: Int) = (0..16 step 8).map { c shr it and 0xFF }.let { it.max() - it.min() }
    val main = coloured.groupBy { hue(it) / 30 }.maxBy { (_, cs) -> cs.sumOf(::chroma) }.value
    fun ch(shift: Int) = main.map { it shr shift and 0xFF }.sorted()[main.size / 2]
    val c = 0xFF shl 24 or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    if (hue(c) < 45 || hue(c) >= 315) return android.graphics.Color.WHITE
    fun tint(shift: Int) = 255 - (255 - (c shr shift and 0xFF)) * 46 / 100
    return 0xFF shl 24 or (tint(16) shl 16) or (tint(8) shl 8) or tint(0)
}

/** Hue of an RGB colour in degrees, 0 until 360. */
fun hue(c: Int): Int {
    val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val b = c and 0xFF
    val max = maxOf(r, g, b); val d = (max - minOf(r, g, b)).toFloat()
    if (d == 0f) return 0
    val h = when (max) { r -> (g - b) / d; g -> 2 + (b - r) / d; else -> 4 + (r - g) / d } * 60
    return ((h + 360) % 360).toInt()
}

/** Visible size of a plated legacy icon, as a part of the shape: Nova's plates measure 0.62 to 0.66. */
const val PLATE_FILL = 0.64f

/**
 * Whether [solid] pixels make an icon shape: about as wide as tall, and close to a superellipse in their box (one
 * of a circle, squircle, rounded or plain square). Not a figure, letters, a shield, a speech bubble or a banner.
 */
private fun iconBody(px: IntArray, n: Int, solid: (Int) -> Boolean): Boolean {
    var t = n; var b = -1; var l = n; var r = -1
    for (i in px.indices) if (solid(px[i])) { val x = i % n; val y = i / n; t = minOf(t, y); b = y; l = minOf(l, x); r = maxOf(r, x) }
    if (b < 0) return false
    val w = r - l + 1f; val h = b - t + 1f
    if (w / h !in 0.88f..1.14f) return false
    return listOf(2.0, 2.5, 3.0, 4.0, 6.0, 12.0).any { e ->
        var both = 0; var either = 0
        for (y in t..b) for (x in l..r) {
            val u = abs((x + 0.5f - l) / w * 2 - 1).toDouble(); val v = abs((y + 0.5f - t) / h * 2 - 1).toDouble()
            val inShape = u.pow(e) + v.pow(e) <= 1; val on = solid(px[y * n + x])
            if (inShape && on) both++; if (inShape || on) either++
        }
        both >= ICON_BODY * either
    }
}

/** How close (intersection over union) an icon's outline must be to a superellipse to be an icon shape. */
const val ICON_BODY = 0.92f

/**
 * The edge colour at 96 angles round an [n]×[n] icon: in from the box's corner to the first solid pixel, then past
 * its anti-aliasing. Null when a ray finds none.
 */
private fun edgeRing(px: IntArray, n: Int): List<Int>? {
    val c = (n - 1) / 2f; val skip = n / 32
    fun at(r: Int, dx: Float, dy: Float): Int? {
        val x = Math.round(c + dx * r); val y = Math.round(c + dy * r)
        return if (x in 0 until n && y in 0 until n) px[y * n + x] else null
    }
    return (0 until 96).map { k ->
        val dx = cos(k * PI / 48).toFloat(); val dy = sin(k * PI / 48).toFloat()
        val hit = (n * 3 / 4 downTo 0).firstOrNull { (at(it, dx, dy) ?: 0) ushr 24 > 200 } ?: return null
        at(maxOf(hit - skip, 0), dx, dy)!!
    }
}

private fun diff(a: Int, b: Int) = (0..16 step 8).sumOf { abs((a shr it and 0xFF) - (b shr it and 0xFF)) }

/**
 * Whether an icon of visible [extent], scaled to just cover [shape] (1.04 of it), loses only background: of its solid
 * pixels the shape cuts off, almost none (a thousandth of the icon, a stray shadow pixel) differ from its edge
 * colour at their angle. Any letter, figure or line that reaches the cut puts the icon on a plate: that strict a
 * bound matched Nova best on the #92 corpus (267 of 324 icons, against 244 with no art check).
 */
private fun keepsArt(px: IntArray, n: Int, extent: Float, shape: BooleanArray): Boolean {
    val ring = edgeRing(px, n) ?: return false
    val c = (n - 1) / 2f; val s = 1.04f / extent
    var art = 0
    for (y in 0 until n) for (x in 0 until n) {
        val p = px[y * n + x]
        if (p ushr 24 <= 200) continue
        val u = Math.round(c + (x - c) * s); val v = Math.round(c + (y - c) * s)
        if (u in 0 until n && v in 0 until n && shape[v * n + u]) continue
        val k = Math.floorMod(Math.round(Math.atan2((y - c).toDouble(), (x - c).toDouble()) / (PI / 48)).toInt(), 96)
        if (diff(p, ring[k]) > 120) art++
    }
    return art <= n * n / 1000
}


/**
 * Fills, in place, every pixel of an [n]×[n] icon layer that isn't fully solid with the icon's colour just inside
 * its edge, toward the middle: the edge stretched outward, so a gradient carries on to the shape's edge. Its
 * anti-aliasing and drop shadow go with it.
 */
fun extendEdge(px: IntArray, n: Int) {
    val src = px.copyOf()
    val c = (n - 1) / 2f; val skip = maxOf(n / 32, 1)
    for (y in 0 until n) for (x in 0 until n) {
        if (src[y * n + x] ushr 24 >= 250) continue
        val dx = c - x; val dy = c - y
        val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
        val ux = dx / len; val uy = dy / len
        var hit = -1
        for (s in 0..len.toInt()) {
            val p = Math.round(y + uy * s) * n + Math.round(x + ux * s)
            if (src[p] ushr 24 >= 250) { hit = s; break }
        }
        if (hit < 0) continue // no solid pixel toward the middle: an empty layer
        val s = minOf(hit + skip, len.toInt())
        val q = src[Math.round(y + uy * s) * n + Math.round(x + ux * s)]
        px[y * n + x] = if (q ushr 24 >= 250) q else src[Math.round(y + uy * hit) * n + Math.round(x + ux * hit)]
    }
}

/**
 * Clears an icon's white plate in place: the light grey, white and see-through pixels reachable from its border, so
 * white marks inside the icon on it stay. [grow] more pixels go inward from there: the icon's edge blended into the
 * plate, opaque and tinted, which showed as a light seam once the shape is filled in the icon's own colour.
 */
fun peelPlate(px: IntArray, n: Int, grow: Int = 0) {
    // Light and near grey: a plate is white with a shaded edge.
    fun plate(c: Int): Boolean {
        val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val b = c and 0xFF
        return c ushr 24 <= 200 || minOf(r, g, b) >= 0xD8 && maxOf(r, g, b) - minOf(r, g, b) <= 16
    }
    val seen = BooleanArray(n * n)
    val todo = ArrayDeque<Int>()
    fun next(p: Int, into: MutableCollection<Int>) {
        val x = p % n
        for (q in intArrayOf(if (x > 0) p - 1 else -1, if (x < n - 1) p + 1 else -1, p - n, p + n))
            if (q in 0 until n * n && !seen[q]) { seen[q] = true; into += q }
    }
    for (i in 0 until n) for (p in intArrayOf(i, (n - 1) * n + i, i * n, i * n + n - 1)) if (!seen[p]) { seen[p] = true; todo += p }
    var edge = ArrayList<Int>() // the first pixels past the plate
    while (todo.isNotEmpty()) {
        val p = todo.removeFirst()
        if (!plate(px[p])) { edge += p; continue }
        px[p] = 0
        next(p, todo)
    }
    repeat(grow) {
        val more = ArrayList<Int>()
        for (p in edge) { px[p] = 0; next(p, more) }
        edge = more
    }
}

/** [legacyFill] without the plate peeling: the one colour of the icon's outer edge, and its visible extent. */
private fun solidEdge(px: IntArray, n: Int, skip: Int = 0): Pair<Int, Float>? {
    val edge = ArrayList<Int>()
    // The first and last solid pixel of each row and column: the icon's outer edge, whatever its outline. [skip]
    // pixels further in, past an opaque blend into a peeled plate.
    for (y in 0 until n) {
        (0 until n).firstOrNull { px[y * n + it] ushr 24 > 200 }?.let { edge += px[y * n + minOf(it + skip, n - 1)] }
        (n - 1 downTo 0).firstOrNull { px[y * n + it] ushr 24 > 200 }?.let { edge += px[y * n + maxOf(it - skip, 0)] }
    }
    for (x in 0 until n) {
        (0 until n).firstOrNull { px[it * n + x] ushr 24 > 200 }?.let { edge += px[minOf(it + skip, n - 1) * n + x] }
        (n - 1 downTo 0).firstOrNull { px[it * n + x] ushr 24 > 200 }?.let { edge += px[maxOf(it - skip, 0) * n + x] }
    }
    if (edge.size < n) return null
    // Each channel's median: anti-aliasing and a stray highlight don't pull it off the plate's colour.
    fun median(shift: Int) = edge.map { it shr shift and 0xFF }.sorted()[edge.size / 2]
    val r = median(16); val g = median(8); val b = median(0)
    val close = edge.count { abs((it shr 16 and 0xFF) - r) + abs((it shr 8 and 0xFF) - g) + abs((it and 0xFF) - b) < 60 }
    if (close < edge.size * 0.85f) return null
    val extent = visibleExtent(px.map { it ushr 24 }, n) ?: return null
    return (0xFF shl 24 or (r shl 16) or (g shl 8) or b) to extent
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
        val listed = ArrayList<String>()
        runCatching {
            readPackXml(res, pkg, "drawable") { p ->
                while (p.next() != XmlPullParser.END_DOCUMENT) if (p.eventType == XmlPullParser.START_TAG && p.name == "item") p.getAttributeValue(null, "drawable")?.let(listed::add)
            }
        }
        (map.values + listed).distinct().sorted()
    }
}

/** What an icon pack's appfilter.xml says: component → drawable name, and how to theme the apps it doesn't list. */
class Appfilter(val map: MutableMap<String, String> = HashMap(), val back: MutableList<String> = ArrayList(),
                var mask: String? = null, var upon: String? = null, var scale: Float = 1f)

/**
 * Reads appfilter.xml from [p] into [into], the one decoder for it: a pack is someone else's file. An entry without a
 * readable component or a drawable is skipped, the first entry for a component wins, and a scale that isn't a positive
 * number is 1.
 * A malformed tag throws, with what was read before it kept in [into]: [readPackXml] stops there.
 */
fun readAppfilter(p: XmlPullParser, into: Appfilter = Appfilter()): Appfilter = into.apply {
    while (p.next() != XmlPullParser.END_DOCUMENT) {
        if (p.eventType != XmlPullParser.START_TAG) continue
        fun attrs() = (0 until p.attributeCount).map { p.getAttributeValue(it) }
        when (p.name) {
            "item" -> {
                val c = parseComponent(p.getAttributeValue(null, "component") ?: continue) ?: continue
                map.putIfAbsent(c, p.getAttributeValue(null, "drawable") ?: continue)
            }
            "iconback" -> back += attrs()
            "iconmask" -> mask = attrs().firstOrNull()
            "iconupon" -> upon = attrs().firstOrNull()
            // NaN, 0 or less turned the theming pad inside out.
            "scale" -> scale = p.getAttributeValue(null, "factor")?.toFloatOrNull()?.takeIf { it > 0f && it.isFinite() } ?: 1f
        }
    }
}

/**
 * Runs [read] over an icon pack's res/xml/[name].xml, or assets/[name].xml for packs that ship it there, and closes
 * it. A malformed tag ends the read but keeps what [read] took before it: one bad entry mustn't drop a whole pack.
 * Throws only when the file isn't there.
 */
private fun readPackXml(res: Resources, pkg: String, name: String, read: (XmlPullParser) -> Unit) {
    fun safely(p: XmlPullParser) = try { read(p) } catch (e: Exception) { android.util.Log.w("Vulcan", "icon pack $pkg: $name.xml stops early", e) }
    val id = res.getIdentifier(name, "xml", pkg)
    if (id != 0) res.getXml(id).let { p -> try { safely(p) } finally { p.close() } }
    else res.assets.open("$name.xml").use { s -> safely(XmlPullParserFactory.newInstance().newPullParser().apply { setInput(s, null) }) }
}

/**
 * Packs already parsed, by package and that package's [android.content.pm.PackageInfo.lastUpdateTime], so picking an
 * icon from a pack that isn't the active one costs no XML parse (#100). Concurrent: icons are also made on the prefetch thread.
 */
private val parsedPacks = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, IconPack>>()

fun loadIconPack(context: Context, pkg: String): IconPack? = runCatching {
    val version = context.packageManager.getPackageInfo(pkg, 0).lastUpdateTime
    parsedPacks[pkg]?.takeIf { it.first == version }?.let { return it.second }
    val res = context.packageManager.getResourcesForApplication(pkg)
    val filter = Appfilter()
    readPackXml(res, pkg, "appfilter") { readAppfilter(it, filter) }
    IconPack(pkg, res, filter.map, filter.back, filter.mask, filter.upon, filter.scale).also { parsedPacks[pkg] = version to it }
}.getOrNull()

/**
 * [icon] drawn once into one [px]-square bitmap. A system adaptive icon keeps its 108 dp layers plus copies cached at
 * draw size (~600 kB at 420 dpi), a pack icon its full 192–512 px image; this is ~110 kB at 64 dp.
 */
fun flatIcon(context: Context, icon: Drawable, px: Int): Drawable = BitmapDrawable(context.resources,
    Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { icon.setBounds(0, 0, px, px); icon.draw(Canvas(it)) })

/**
 * Whether cached app icons are hardware bitmaps (AppRepo.hardware). From Android 15, the oldest version checked: on
 * Android 11 the system draws a drag's shadow into a software canvas, which refuses hardware bitmaps, so no icon,
 * folder or page could be dragged.
 * shortcut: Android 12 to 14 are untested and so left out; lower this once drags are checked there.
 */
val HARDWARE_ICONS = android.os.Build.VERSION.SDK_INT >= 35

/**
 * What [draw] paints, as a [w]×[h] bitmap. A bitmap's own canvas refuses hardware bitmaps too, so with
 * [HARDWARE_ICONS] the drawing is recorded and rendered by the GPU, and the result is a hardware bitmap.
 */
fun rendered(w: Int, h: Int, draw: (Canvas) -> Unit): Bitmap =
    if (HARDWARE_ICONS) Bitmap.createBitmap(android.graphics.Picture().apply { draw(beginRecording(w, h)); endRecording() })
    else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { draw(Canvas(it)) }

/**
 * A picture of views ([draw] is a View.draw), as a [w]×[h] bitmap: page thumbnails and a held page's drag shadow.
 * With [HARDWARE_ICONS] it goes through the same renderer a window uses. [rendered]'s recording lost every hardware
 * bitmap on Android 17 (a Galaxy S23): widgets drew, app icons and folder contents did not.
 */
fun snapshot(w: Int, h: Int, draw: (Canvas) -> Unit): Bitmap {
    if (android.os.Build.VERSION.SDK_INT < 35) return rendered(w, h, draw) // as HARDWARE_ICONS, spelled out for lint
    val node = android.graphics.RenderNode("snapshot").apply { setPosition(0, 0, w, h) }
    val reader = android.media.ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 1,
        android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
    val renderer = android.graphics.HardwareRenderer()
    try {
        draw(node.beginRecording()); node.endRecording()
        renderer.isOpaque = false
        renderer.setSurface(reader.surface)
        renderer.setContentRoot(node)
        renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
        // The bitmap keeps the buffer alive after the image and the reader close.
        return reader.acquireNextImage().use { img -> img.hardwareBuffer!!.use { Bitmap.wrapHardwareBuffer(it, null)!! } }
    } finally { renderer.destroy(); reader.close(); node.discardDisplayList() }
}

/**
 * Each app's finished icon and label on disk, as Launcher3's IconCache keeps them. Asking the app itself opens its APK,
 * and its resource table stays mapped until a GC (Gmail's is 10 MB): with this, a start opens only apps that changed.
 * An entry holds while the app's APK, Vulcan's own and the system build match, and [looks] ([Icons.signature]) or the locale.
 */
class AppCache(context: Context, private val looks: String) {
    private val dir = File(context.cacheDir, "apps").apply { mkdirs() }
    private val locale = context.resources.configuration.locales.toLanguageTags()
    // Vulcan's own APK too: an update that draws icons differently (#92) must not keep serving the old ones.
    private val self = File(context.applicationInfo.sourceDir).lastModified()

    private fun name(key: String) = key.replace('/', '#')
    private fun stamp(a: App, extra: String) =
        a.info.applicationInfo.sourceDir.let { "$it|${File(it).lastModified()}|${android.os.Build.FINGERPRINT}|$self|$extra" }

    fun label(a: App): String {
        val f = File(dir, name(a.key) + ".label"); val s = stamp(a, locale)
        read(f, s) { it.readUTF() }?.let { return it }
        return a.info.label.toString().also { l -> write(f, s) { it.writeUTF(l) } }
    }

    fun icon(a: App, custom: String, make: () -> Bitmap): Bitmap {
        val f = File(dir, name(a.key) + ".png"); val s = stamp(a, "$looks|$custom")
        read(f, s) { BitmapFactory.decodeStream(it) }?.let { return it }
        // PNG encoding takes a few ms an icon; nothing draws into the bitmap again, so it's encoded off the UI thread.
        return make().also { b -> writer.execute { write(f, s) { b.compress(Bitmap.CompressFormat.PNG, 100, it) } } }
    }

    /** Drops the entries of [pkgs]: an app can change its icon or label without an update. */
    fun forget(pkgs: Collection<String>) = dir.listFiles()?.forEach { f -> if (pkgs.any { f.name.startsWith("$it#") }) f.delete() }
    /** Drops the entries of apps not in [keys] (uninstalled while the launcher wasn't running). */
    fun keepOnly(keys: Collection<String>) {
        val names = keys.map(::name).toSet()
        dir.listFiles()?.forEach { f -> if (f.name.substringBeforeLast('.') !in names) f.delete() }
    }

    private fun <T> read(f: File, stamp: String, body: (DataInputStream) -> T?): T? =
        runCatching { DataInputStream(f.inputStream().buffered()).use { if (it.readUTF() == stamp) body(it) else null } }.getOrNull()

    private fun write(f: File, stamp: String, body: (DataOutputStream) -> Unit) {
        val tmp = File(f.path + ".tmp")
        // A failed write (a full disk) leaves no half-written .tmp behind until the next keepOnly.
        runCatching { DataOutputStream(tmp.outputStream().buffered()).use { it.writeUTF(stamp); body(it) }; check(tmp.renameTo(f)) }
            .onFailure { tmp.delete() }
    }

    companion object { private val writer = java.util.concurrent.Executors.newSingleThreadExecutor() }
}

/** The largest power-of-two subsample that keeps a [side]-px image at least [target] px. */
fun sampleSize(side: Int, target: Int): Int = Integer.highestOneBit((side / target.coerceAtLeast(1)).coerceAtLeast(1))

/**
 * A picked image icon as its URI and whether it is drawn in the icon shape: "uri:<uri>" is shaped, "uriraw:<uri>" is
 * not (the edit dialog's per-icon opt-out, #92). Null for any other custom icon.
 */
fun imageIcon(custom: String): Pair<String, Boolean>? = when {
    custom.startsWith("uriraw:") -> custom.removePrefix("uriraw:") to false
    custom.startsWith("uri:") -> custom.removePrefix("uri:") to true
    else -> null
}

/** App icons with the chosen icon pack, shape and legacy-icon options applied. Built once per activity. */
class Icons(private val context: Context, private val looks: Looks) {
    private val outline = shapeOutline(looks.str("shape"))
    val pack: IconPack? = looks.iconPack.takeIf { it.isNotEmpty() }?.let { loadIconPack(context, it) }
    /** The largest size any surface shows an icon at, in px: every icon is drawn once at this size (see [flat]). */
    private val size = (listOf("iconSize", "dockIconSize", "drawerIconSize").maxOf { looks.int(it) } * context.resources.displayMetrics.density).toInt()
    /**
     * Themed icons (Android 13+): an app's monochrome layer in the system's wallpaper colours, light or dark, as the
     * platform's own launcher pairs them. Null when off.
     */
    private val themeColors: Pair<Int, Int>? = if (android.os.Build.VERSION.SDK_INT >= 33 && looks.on("themedIcons")) {
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        if (night) context.getColor(android.R.color.system_neutral1_800) to context.getColor(android.R.color.system_accent1_100)
        else context.getColor(android.R.color.system_accent1_100) to context.getColor(android.R.color.system_neutral2_700)
    } else null

    /**
     * Everything that shapes an icon here, for [AppCache]: the pack and its version, the options, the size, the themed
     * colours and the system's adaptive-icon mask, which a theme can change. Dark mode only through the themed colours:
     * with it in, every day/night flip redrew every icon.
     * ponytail: an app's own night icon variant (rare) shows after its next update; key the cache per mode if one matters.
     */
    val signature: String = listOf(looks.iconPack, pack?.let { runCatching { context.packageManager.getPackageInfo(it.pkg, 0).lastUpdateTime }.getOrNull() },
        looks.str("shape"), looks.str("reshape"), looks.on("normalize"), looks.on("autogen"), size, themeColors,
        context.resources.getIdentifier("config_icon_mask", "string", "android").takeIf { it != 0 }?.let { context.resources.getString(it) },
    ).joinToString("|")

    /** [base] is the app's own icon; [custom] an "pack:<pkg>:<name>" or "uri:<uri>" override. */
    fun get(pkg: String, cls: String, custom: String, base: () -> Drawable): Drawable {
        custom(custom)?.let { return it }
        val themed = pack?.map?.get("$pkg/$cls")?.let { pack.drawable(it) }
        var icon = themed ?: base()
        // An icon pack's own icon wins; apps with no monochrome layer keep their colours.
        if (themed == null && themeColors != null && android.os.Build.VERSION.SDK_INT >= 33) (icon as? AdaptiveIconDrawable)?.monochrome?.let { mono ->
            icon = AdaptiveIconDrawable(ColorDrawable(themeColors.first), mono.mutate().apply { setTint(themeColors.second) })
        }
        if (themed == null && pack != null && looks.on("autogen") && pack.back.isNotEmpty()) autogen(icon, pkg)?.let { return it }
        return finished(icon, fromPack = themed != null)
    }

    /** A legacy icon reshaped or normalized as the settings say (a pack's own icons only by "always"), then [drawn]. */
    private fun finished(icon: Drawable, fromPack: Boolean): Drawable {
        if (icon is AdaptiveIconDrawable) return drawn(icon)
        val reshape = looks.str("reshape")
        return drawn(when {
            reshape == "always" || reshape == "unthemed" && !fromPack -> reshaped(icon)
            looks.on("normalize") -> normalized(icon)
            else -> icon
        })
    }

    /** The last step of every icon: flattened, and clipped to the shape when it is an adaptive one. */
    private fun drawn(icon: Drawable) = flat(if (outline != null && icon is AdaptiveIconDrawable) ShapedIcon(icon, outline) else icon)

    private fun flat(icon: Drawable) = flatIcon(context, icon, size)

    /** The shape's visible area as a 64×64 mask, for [legacyFill] to see what it would cut off. */
    private val shapeMask by lazy {
        val n = 64
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawPath(shapePath(looks.str("shape"), Rect(0, 0, n, n)), Paint(Paint.ANTI_ALIAS_FLAG))
        val px = IntArray(n * n).also { bmp.getPixels(it, 0, n, 0, 0, n, n) }
        BooleanArray(n * n) { px[it] ushr 24 >= 128 }
    }

    /**
     * A legacy icon as an adaptive one, so the shape clips it: an icon shape that loses none of its art fills the
     * shape, its edge stretched out ([legacyFill]); any other sits on a plate ([plateTint]).
     */
    private fun reshaped(icon: Drawable): Drawable {
        val n = 64
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, n, n); icon.draw(Canvas(bmp))
        val px = IntArray(n * n).also { bmp.getPixels(it, 0, n, 0, 0, n, n) }
        val (color, extent, peel) = legacyFill(px, n, shapeMask) ?: run {
            // The layer is 108 units with the shape's 72 in its middle: the icon's visible part takes PLATE_FILL of it.
            val visible = visibleExtent(px.map { it ushr 24 }, n) ?: 1f
            val inset = ((1 - 2 * PLATE_FILL / (3 * visible)) / 2).coerceIn(0f, 0.4f)
            return AdaptiveIconDrawable(ColorDrawable(plateTint(px, n)), InsetDrawable(icon, inset))
        }
        // The icon's visible part is scaled to just past the shape's edge (1.04), so its own rounded corners are
        // clipped away and its edge colour fills the rest.
        val fill = ((1 - 2 * 1.04f / (3 * extent)) / 2).coerceIn(-0.25f, 0.4f)
        val m = (2 * size).coerceAtLeast(n)
        val layer = Bitmap.createBitmap(m, m, Bitmap.Config.ARGB_8888)
        if (peel) {
            // A peeled icon is small inside its plate: enlarged to the shape's edge it went soft (#92). It grows only
            // up to its own pixels (never smaller than on its plate, 1/6), and its edge colour fills the shape around
            // it. The plate is cleared from a full-size copy, so none of it shows over the fill.
            val src = (icon as? BitmapDrawable)?.bitmap?.width ?: icon.intrinsicWidth
            val inset = maxOf(fill, minOf((1 - src / (1.5f * size)) / 2, 1 / 6f))
            val big = Bitmap.createBitmap(m, m, Bitmap.Config.ARGB_8888)
            icon.setBounds(0, 0, m, m); icon.draw(Canvas(big))
            val all = IntArray(m * m).also { big.getPixels(it, 0, m, 0, 0, m, m) }
            peelPlate(all, m, grow = maxOf(1, m / 48)); big.setPixels(all, 0, m, 0, 0, m, m)
            return AdaptiveIconDrawable(ColorDrawable(color), InsetDrawable(BitmapDrawable(context.resources, big), inset))
        }
        // The whole layer drawn once, the icon's edge stretched out over its corners and any shadow or halo past it.
        InsetDrawable(icon, fill).apply { setBounds(0, 0, m, m); draw(Canvas(layer)) }
        val all = IntArray(m * m).also { layer.getPixels(it, 0, m, 0, 0, m, m) }
        extendEdge(all, m); layer.setPixels(all, 0, m, 0, 0, m, m)
        return AdaptiveIconDrawable(ColorDrawable(color), BitmapDrawable(context.resources, layer))
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

    /**
     * Item-level custom icons drawn so far, by their custom string: home re-resolves every item on each render, and an
     * image or pack icon costs a decode. Held for this [Icons], so it already matches the settings that shape them.
     * ponytail: an "app:" icon whose target app changes keeps the old face until home rebuilds; drop entries on a package change if that shows.
     */
    private val customs = android.util.LruCache<String, Drawable>(32)

    fun custom(custom: String): Drawable? {
        if (custom.isEmpty()) return null
        customs.get(custom)?.let { return it }
        return customUncached(custom)?.also { customs.put(custom, it) }
    }

    private fun customUncached(custom: String): Drawable? {
        // A picked image takes the icon shape unless this icon opted out (uriraw:), whatever Reshape legacy icons says,
        // as Nova does (#92, #132).
        imageIcon(custom)?.let { (uri, reshape) ->
            return image(Uri.parse(uri))?.let { if (reshape) drawn(reshaped(it)) else flat(it) }
        }
        return when {
            // Another app's icon, as this launcher draws it (pack, theming, shape). Gone app: null, so the default shows.
            custom.startsWith("app:") -> custom.removePrefix("app:").split("/").takeIf { it.size == 2 }?.let { (pkg, cls) ->
                runCatching { context.packageManager.getActivityIcon(android.content.ComponentName(pkg, cls)) }.getOrNull()
                    ?.let { base -> get(pkg, cls, "") { base } }
            }
            // A pack icon picked by hand is shaped like the pack's own icons (#132).
            custom.startsWith("pack:") -> {
                val (pkg, name) = custom.removePrefix("pack:").split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                (if (pkg == pack?.pkg) pack else loadIconPack(context, pkg))?.drawable(name)?.let { finished(it, fromPack = true) }
            }
            else -> null
        }
    }

    private fun image(uri: Uri): Drawable? = runCatching {
        // A photo picked as an icon can be 12 MP (48 MB decoded): read its size first and decode it subsampled.
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
        opts.inSampleSize = sampleSize(minOf(opts.outWidth, opts.outHeight), size); opts.inJustDecodeBounds = false
        context.contentResolver.openInputStream(uri)!!.use {
            // Null for a file that isn't a readable image: the default icon shows, not an empty one.
            android.graphics.BitmapFactory.decodeStream(it, null, opts)?.let { b -> BitmapDrawable(context.resources, b) }
        }
    }.getOrNull()

    /** ADW-style theming for apps the pack doesn't list: back plate, masked and scaled icon, overlay. */
    private fun autogen(icon: Drawable, pkg: String): Drawable? {
        val p = pack ?: return null
        // abs of the remainder, not of the hash: abs(Int.MIN_VALUE) stays negative; this keeps every other app's pick.
        val back = p.drawable(p.back[abs(pkg.hashCode() % p.back.size)]) ?: return null
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
