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
 * How a legacy icon fills its shape: on [color], or with [extend] its own edge stretched out to the shape's (a
 * gradient); [extent] is its visible size, to scale it to the shape's edge; [peel] clears a system plate off it first.
 */
data class LegacyFill(val color: Int, val extent: Float, val peel: Boolean = false, val extend: Boolean = false)

/**
 * How to reshape a legacy icon, from its [n]×[n] ARGB pixels (row-major), when it's a solid shape (a square, rounded
 * square or circle icon): on its edge's one colour, or its edge stretched when that changes smoothly. Null for any
 * other icon (a mascot, a cut-out logo, a photo to its edge): it goes on a white plate instead. As Nova reshapes them
 * (#92).
 */
fun legacyFill(px: IntArray, n: Int): LegacyFill? = plateFill(px, n) ?: smoothEdge(px, n)

private fun plateFill(px: IntArray, n: Int): LegacyFill? {
    val outer = solidEdge(px, n)?.let { (fill, extent) -> LegacyFill(fill, extent) }
    if (outer != null && (0..16 step 8).any { outer.color shr it and 0xFF < 230 }) return outer
    // A white plate with a solid icon inside: the system's (EMUI puts every unthemed icon on one, #92). Peel it, and
    // fill the shape on the inner icon's own edge colour, if that icon is a solid shape, not a mark on the plate.
    val inner = px.copyOf().also { peelPlate(it, n) }
    val opaque = { a: IntArray -> a.count { it ushr 24 > 200 } }
    if (opaque(px) - opaque(inner) < n * n / 4) return outer
    val extent = visibleExtent(px.map { it ushr 24 }, n) ?: return outer
    val (innerFill, innerExtent) = solidEdge(inner, n, n / 32)?.takeIf { it.second < extent * 0.9f } ?: return outer
    return if (solidShape(inner, n) { it != 0 }) LegacyFill(innerFill, innerExtent, peel = true) else outer
}

/** Row spans over the bounding box of the [solid] pixels: ~0.95 for a rounded square, 0.79 for a circle, well under for a figure. */
private fun solidShape(px: IntArray, n: Int, solid: (Int) -> Boolean): Boolean {
    var area = 0; var t = n; var b = -1; var l = n; var r = -1
    for (y in 0 until n) {
        val a = (0 until n).firstOrNull { solid(px[y * n + it]) } ?: continue
        val z = (n - 1 downTo 0).first { solid(px[y * n + it]) }
        area += z - a + 1; t = minOf(t, y); b = y; l = minOf(l, a); r = maxOf(r, z)
    }
    return b >= 0 && area >= 0.72f * (r - l + 1) * (b - t + 1)
}

/**
 * A solid-shape icon whose edge isn't one colour but changes smoothly around it (a gradient, a two-tone split): its
 * edge is stretched out to fill the shape ([extendEdge]). Null when the edge is busy (a photo, a game's art).
 */
private fun smoothEdge(px: IntArray, n: Int): LegacyFill? {
    if (!solidShape(px, n) { it ushr 24 > 200 }) return null
    val c = (n - 1) / 2f; val skip = n / 32
    fun at(r: Int, dx: Float, dy: Float): Int? {
        val x = Math.round(c + dx * r); val y = Math.round(c + dy * r)
        return if (x in 0 until n && y in 0 until n) px[y * n + x] else null
    }
    // The edge colour at 96 angles: in from the box's corner to the first solid pixel, then past its anti-aliasing.
    val ring = (0 until 96).map { k ->
        val dx = cos(k * PI / 48).toFloat(); val dy = sin(k * PI / 48).toFloat()
        val hit = (n * 3 / 4 downTo 0).firstOrNull { (at(it, dx, dy) ?: 0) ushr 24 > 200 } ?: return null
        at(maxOf(hit - skip, 0), dx, dy)!!
    }
    fun diff(a: Int, b: Int) = (0..16 step 8).sumOf { abs((a shr it and 0xFF) - (b shr it and 0xFF)) }
    val steps = ring.indices.map { diff(ring[it], ring[(it + 1) % ring.size]) }
    // A gradient moves a little at each step; a split jumps a few times; a busy edge jumps all the way round.
    if (steps.average() > 16 || steps.count { it > 90 } > 4) return null
    fun mean(shift: Int) = ring.sumOf { it shr shift and 0xFF } / ring.size
    val extent = visibleExtent(px.map { it ushr 24 }, n) ?: return null
    return LegacyFill(0xFF shl 24 or (mean(16) shl 16) or (mean(8) shl 8) or mean(0), extent, extend = true)
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
 * white marks inside the icon on it stay.
 */
fun peelPlate(px: IntArray, n: Int) {
    // Light and near grey: a plate is white with a shaded edge.
    fun plate(c: Int): Boolean {
        val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val b = c and 0xFF
        return c ushr 24 <= 200 || minOf(r, g, b) >= 0xD8 && maxOf(r, g, b) - minOf(r, g, b) <= 16
    }
    val seen = BooleanArray(n * n)
    val todo = ArrayDeque<Int>()
    for (i in 0 until n) for (p in intArrayOf(i, (n - 1) * n + i, i * n, i * n + n - 1)) if (!seen[p]) { seen[p] = true; todo += p }
    while (todo.isNotEmpty()) {
        val p = todo.removeFirst()
        if (!plate(px[p])) continue
        px[p] = 0
        val x = p % n
        for (q in intArrayOf(if (x > 0) p - 1 else -1, if (x < n - 1) p + 1 else -1, p - n, p + n))
            if (q in 0 until n * n && !seen[q]) { seen[q] = true; todo += q }
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

/**
 * [icon] drawn once into one [px]-square bitmap. A system adaptive icon keeps its 108 dp layers plus copies cached at
 * draw size (~600 kB at 420 dpi), a pack icon its full 192–512 px image; this is ~110 kB at 64 dp.
 */
fun flatIcon(context: Context, icon: Drawable, px: Int): Drawable = BitmapDrawable(context.resources,
    Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { icon.setBounds(0, 0, px, px); icon.draw(Canvas(it)) })

/**
 * Each app's finished icon and label on disk, as Launcher3's IconCache keeps them. Asking the app itself opens its APK,
 * and its resource table stays mapped until a GC (Gmail's is 10 MB): with this, a start opens only apps that changed.
 * An entry holds while the app's APK and the system build match, and [looks] ([Icons.signature]) or the locale.
 */
class AppCache(context: Context, private val looks: String) {
    private val dir = File(context.cacheDir, "apps").apply { mkdirs() }
    private val locale = context.resources.configuration.locales.toLanguageTags()

    private fun name(key: String) = key.replace('/', '#')
    private fun stamp(a: App, extra: String) =
        a.info.applicationInfo.sourceDir.let { "$it|${File(it).lastModified()}|${android.os.Build.FINGERPRINT}|$extra" }

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
        runCatching { DataOutputStream(tmp.outputStream().buffered()).use { it.writeUTF(stamp); body(it) }; tmp.renameTo(f) }
    }

    companion object { private val writer = java.util.concurrent.Executors.newSingleThreadExecutor() }
}

/** The largest power-of-two subsample that keeps a [side]-px image at least [target] px. */
fun sampleSize(side: Int, target: Int): Int = Integer.highestOneBit((side / target.coerceAtLeast(1)).coerceAtLeast(1))

/** App icons with the chosen icon pack, shape and legacy-icon options applied. Built once per activity. */
class Icons(private val context: Context, private val looks: Looks) {
    private val outline = shapeOutline(looks.str("shape"))
    val pack: IconPack? = looks.iconPack.takeIf { it.isNotEmpty() }?.let { loadIconPack(context, it) }
    /** The largest size any surface shows an icon at, in px: every icon is drawn once at this size (see [flat]). */
    private val size = (listOf("iconSize", "dockIconSize", "drawerIconSize").maxOf { looks.int(it) } * context.resources.displayMetrics.density).toInt()
    /**
     * Everything that shapes an icon here, for [AppCache]: the pack and its version, the options, the size, dark mode
     * (an icon can have a night variant) and the system's adaptive-icon mask, which a theme can change.
     */
    /**
     * Themed icons (Android 13+): an app's monochrome layer in the system's wallpaper colours, light or dark, as the
     * platform's own launcher pairs them. Null when off.
     */
    private val themeColors: Pair<Int, Int>? = if (android.os.Build.VERSION.SDK_INT >= 33 && looks.on("themedIcons")) {
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        if (night) context.getColor(android.R.color.system_neutral1_800) to context.getColor(android.R.color.system_accent1_100)
        else context.getColor(android.R.color.system_accent1_100) to context.getColor(android.R.color.system_neutral2_700)
    } else null

    val signature: String = listOf(looks.iconPack, pack?.let { runCatching { context.packageManager.getPackageInfo(it.pkg, 0).lastUpdateTime }.getOrNull() },
        looks.str("shape"), looks.str("reshape"), looks.on("normalize"), looks.on("autogen"), size, themeColors,
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK,
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
        val reshape = looks.str("reshape")
        if (icon !is AdaptiveIconDrawable && (reshape == "always" || reshape == "unthemed" && themed == null)) {
            icon = reshaped(icon)
        } else if (icon !is AdaptiveIconDrawable && looks.on("normalize")) {
            icon = normalized(icon)
        }
        return flat(if (outline != null && icon is AdaptiveIconDrawable) ShapedIcon(icon, outline) else icon)
    }

    private fun flat(icon: Drawable) = flatIcon(context, icon, size)

    /**
     * A legacy icon as an adaptive one, so the shape clips it: one with a solid edge fills the shape on its own edge
     * colour, or one with a smooth gradient edge has it stretched out ([legacyFill]); any other sits on a white plate.
     */
    private fun reshaped(icon: Drawable): Drawable {
        val n = 64
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, n, n); icon.draw(Canvas(bmp))
        val px = IntArray(n * n).also { bmp.getPixels(it, 0, n, 0, 0, n, n) }
        val (color, extent, peel, extend) = legacyFill(px, n) ?: return AdaptiveIconDrawable(ColorDrawable(android.graphics.Color.WHITE), InsetDrawable(icon, 0.28f))
        // The layer is 108 units with the shape's 72 in its middle: the icon's visible part is scaled to just past
        // the shape's edge (1.04), so its own rounded corners are clipped away and its edge colour fills the rest.
        val inset = ((1 - 2 * 1.04f / (3 * extent)) / 2).coerceIn(-0.25f, 0.4f)
        if (extend) {
            // The whole layer drawn once, the icon's edge stretched out over the corners it leaves.
            val m = (2 * size).coerceAtLeast(n)
            val layer = Bitmap.createBitmap(m, m, Bitmap.Config.ARGB_8888)
            InsetDrawable(icon, inset).apply { setBounds(0, 0, m, m); draw(Canvas(layer)) }
            val all = IntArray(m * m).also { layer.getPixels(it, 0, m, 0, 0, m, m) }
            extendEdge(all, m); layer.setPixels(all, 0, m, 0, 0, m, m)
            return AdaptiveIconDrawable(ColorDrawable(color), BitmapDrawable(context.resources, layer))
        }
        val drawn = if (!peel) icon else {
            // The plate cleared from a full-size copy, so none of it shows over the fill inside the shape.
            val m = (2 * size).coerceAtLeast(n) // the inner icon is enlarged to fill the shape
            val big = Bitmap.createBitmap(m, m, Bitmap.Config.ARGB_8888)
            icon.setBounds(0, 0, m, m); icon.draw(Canvas(big))
            val all = IntArray(m * m).also { big.getPixels(it, 0, m, 0, 0, m, m) }
            peelPlate(all, m); big.setPixels(all, 0, m, 0, 0, m, m)
            BitmapDrawable(context.resources, big)
        }
        return AdaptiveIconDrawable(ColorDrawable(color), InsetDrawable(drawn, inset))
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
        // Another app's icon, as this launcher draws it (pack, theming, shape). Gone app: null, so the default shows.
        custom.startsWith("app:") -> custom.removePrefix("app:").split("/").let { (pkg, cls) ->
            runCatching { context.packageManager.getActivityIcon(android.content.ComponentName(pkg, cls)) }.getOrNull()
                ?.let { base -> get(pkg, cls, "") { base } }
        }
        custom.startsWith("pack:") -> {
            val (pkg, name) = custom.removePrefix("pack:").split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            (if (pkg == pack?.pkg) pack else loadIconPack(context, pkg))?.drawable(name)
        }
        custom.startsWith("uri:") -> runCatching {
            // A photo picked as an icon can be 12 MP (48 MB decoded): read its size first and decode it subsampled.
            val uri = Uri.parse(custom.removePrefix("uri:"))
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
            opts.inSampleSize = sampleSize(minOf(opts.outWidth, opts.outHeight), size); opts.inJustDecodeBounds = false
            context.contentResolver.openInputStream(uri)!!.use {
                BitmapDrawable(context.resources, android.graphics.BitmapFactory.decodeStream(it, null, opts))
            }
        }.getOrNull()
        else -> null
    }?.let(::flat)

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
