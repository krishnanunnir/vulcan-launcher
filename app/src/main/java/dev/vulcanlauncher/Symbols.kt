package dev.vulcanlauncher

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

/** Action → symbol for action shortcuts on the home screen. */
val ACTION_SYMBOLS = mapOf("now" to "sun", "drawer" to "apps", "notifications" to "notifications", "quicksettings" to "settings",
    "recents" to "recents", "previews" to "carousel", "lock" to "lock", "power" to "power", "search" to "search", "voice" to "mic",
    "assistant" to "sparkle", "settings" to "settings", "defaultpage" to "home", "widgets" to "widgets", "wallpaper" to "image",
    "toggledock" to "dock", "togglebar" to "notifications", "torch" to "flashlight", "none" to "close")

/**
 * Outlined icons in the Material Symbols style, drawn on a 24-unit grid with round 2-unit strokes.
 * Drawn here (not copied from an icon font) so the APK carries no third-party assets.
 */
class Symbol(private val name: String, private val color: Int, private val size: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var opacity = 255
    private lateinit var c: Canvas

    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size

    private fun stroke() { paint.style = Paint.Style.STROKE }
    private fun fill() { paint.style = Paint.Style.FILL }
    private fun line(x1: Float, y1: Float, x2: Float, y2: Float) { stroke(); c.drawLine(x1, y1, x2, y2, paint) }
    private fun circle(x: Float, y: Float, r: Float) { stroke(); c.drawCircle(x, y, r, paint) }
    private fun dot(x: Float, y: Float, r: Float = 1.7f) { fill(); c.drawCircle(x, y, r, paint) }
    private fun rect(l: Float, t: Float, r: Float, b: Float, rad: Float, filled: Boolean = false) {
        if (filled) fill() else stroke()
        c.drawRoundRect(RectF(l, t, r, b), rad, rad, paint)
    }
    private fun arc(l: Float, t: Float, r: Float, b: Float, start: Float, sweep: Float) { stroke(); c.drawArc(RectF(l, t, r, b), start, sweep, false, paint) }
    private fun poly(vararg p: Float, close: Boolean = false, filled: Boolean = false) {
        val path = Path().apply {
            moveTo(p[0], p[1])
            for (i in 2 until p.size step 2) lineTo(p[i], p[i + 1])
            if (close) close()
        }
        if (filled) fill() else stroke()
        c.drawPath(path, paint)
    }
    private fun path(filled: Boolean = false, build: Path.() -> Unit) { if (filled) fill() else stroke(); c.drawPath(Path().apply(build), paint) }
    private fun eye() {
        path { moveTo(2f, 12f); quadTo(12f, 2.5f, 22f, 12f); quadTo(12f, 21.5f, 2f, 12f); close() }
        circle(12f, 12f, 3f)
    }

    override fun draw(canvas: Canvas) {
        c = canvas
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        paint.color = color
        paint.alpha = (color ushr 24) * opacity / 255
        // The back arrow points the way back: right in right-to-left languages.
        if (name == "back" && layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) canvas.scale(-1f, 1f, 12f, 12f)
        when (name) {
            "add" -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            "close" -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            "back" -> { line(19f, 12f, 5f, 12f); poly(11f, 6f, 5f, 12f, 11f, 18f) }
            "left" -> poly(15f, 6f, 9f, 12f, 15f, 18f)
            "right" -> poly(9f, 6f, 15f, 12f, 9f, 18f)
            "sort" -> { line(4f, 7f, 20f, 7f); line(4f, 12f, 15f, 12f); line(4f, 17f, 10f, 17f) }
            "check" -> poly(5f, 12.5f, 10f, 17.5f, 19f, 7f)
            "search" -> { circle(10.5f, 10.5f, 6.5f); line(15.5f, 15.5f, 20f, 20f) }
            "mic" -> { rect(9f, 3f, 15f, 14f, 3f); arc(6f, 5f, 18f, 17f, 0f, 180f); line(12f, 17f, 12f, 21f) }
            "more" -> { dot(12f, 5f); dot(12f, 12f); dot(12f, 19f) }
            "apps" -> for (x in listOf(6f, 12f, 18f)) for (y in listOf(6f, 12f, 18f)) dot(x, y, 1.9f)
            "widgets" -> { rect(3.5f, 3.5f, 10.5f, 10.5f, 2f); rect(13.5f, 3.5f, 20.5f, 10.5f, 2f); rect(3.5f, 13.5f, 10.5f, 20.5f, 2f); rect(13.5f, 13.5f, 20.5f, 20.5f, 2f) }
            "flashlight" -> { poly(7f, 3f, 17f, 3f, 17f, 7f, 14.5f, 11f, 14.5f, 21f, 9.5f, 21f, 9.5f, 11f, 7f, 7f, close = true); line(7f, 7f, 17f, 7f); dot(12f, 14.5f, 1.2f) }
            "bolt" -> poly(13f, 3f, 5f, 14f, 12f, 14f, 11f, 21f, 19f, 10f, 12f, 10f, close = true)
            "image" -> { rect(3f, 4f, 21f, 20f, 3f); circle(8.5f, 9.5f, 1.5f); poly(3f, 17f, 9f, 11.5f, 13f, 15f, 16f, 12.5f, 21f, 17f) }
            "carousel" -> { rect(7f, 4f, 17f, 20f, 2f); line(3f, 7f, 3f, 17f); line(21f, 7f, 21f, 17f) }
            "lock", "unlock" -> {
                rect(5f, 11f, 19f, 21f, 2f)
                arc(8f, 3f, 16f, 11f, 180f, if (name == "lock") 180f else 140f)
                line(8f, 7f, 8f, 11f)
                if (name == "lock") line(16f, 7f, 16f, 11f)
                dot(12f, 16f, 1.4f)
            }
            "settings" -> { line(4f, 7f, 7f, 7f); circle(9.5f, 7f, 2.5f); line(12f, 7f, 20f, 7f); line(4f, 17f, 12f, 17f); circle(14.5f, 17f, 2.5f); line(17f, 17f, 20f, 17f) }
            "edit" -> { poly(4f, 20f, 5f, 15.5f, 15f, 5.5f, 18.5f, 9f, 8.5f, 19f, close = true); line(13f, 7.5f, 16.5f, 11f) }
            "delete" -> { line(4f, 7f, 20f, 7f); poly(9f, 7f, 9f, 4f, 15f, 4f, 15f, 7f); poly(6f, 7f, 7f, 20f, 17f, 20f, 18f, 7f); line(10f, 11f, 10f, 16f); line(14f, 11f, 14f, 16f) }
            "info" -> { circle(12f, 12f, 9f); line(12f, 11f, 12f, 16.5f); dot(12f, 7.8f, 1.3f) }
            "resize" -> { poly(14f, 4f, 20f, 4f, 20f, 10f); line(20f, 4f, 13.5f, 10.5f); poly(10f, 20f, 4f, 20f, 4f, 14f); line(4f, 20f, 10.5f, 13.5f) }
            "show" -> eye()
            "hide" -> { eye(); line(4f, 4f, 20f, 20f) }
            "folder" -> poly(3f, 6.5f, 3.5f, 5f, 9.5f, 5f, 11.5f, 7.5f, 20.5f, 7.5f, 21f, 9f, 21f, 18.5f, 20f, 19.5f, 4f, 19.5f, 3f, 18.5f, close = true)
            "download" -> { line(12f, 4f, 12f, 15f); poly(7f, 10f, 12f, 15f, 17f, 10f); line(5f, 20f, 19f, 20f) }
            "stop" -> { circle(12f, 12f, 9f); rect(9f, 9f, 15f, 15f, 1f, filled = true) }
            "notifications" -> {
                path { moveTo(6f, 17f); lineTo(6f, 11f); arcTo(RectF(6f, 5f, 18f, 17f), 180f, 180f); lineTo(18f, 17f) }
                line(4f, 17f, 20f, 17f); line(10.5f, 20.5f, 13.5f, 20.5f); line(12f, 3f, 12f, 5f)
            }
            "clock" -> { circle(12f, 12f, 9f); poly(12f, 7f, 12f, 12f, 15.5f, 14f) }
            "home" -> { poly(4f, 10.5f, 12f, 4f, 20f, 10.5f, 20f, 20f, 4f, 20f, close = true); poly(10f, 20f, 10f, 14.5f, 14f, 14.5f, 14f, 20f) }
            "dock" -> { rect(5.5f, 2.5f, 18.5f, 21.5f, 3f); line(9f, 18f, 15f, 18f) }
            "palette" -> { circle(12f, 12f, 9f); dot(7.5f, 11f); dot(10f, 7f); dot(14.5f, 7f); dot(17f, 11f) }
            "moon" -> path { addCircle(11f, 13f, 8f, Path.Direction.CW); op(Path().apply { addCircle(17f, 7f, 6.5f, Path.Direction.CW) }, Path.Op.DIFFERENCE) }
            "sun" -> { circle(12f, 12f, 4f); for (k in 0 until 8) { val t = Math.toRadians(k * 45.0); line(12 + 7 * cos(t).toFloat(), 12 + 7 * sin(t).toFloat(), 12 + 9.5f * cos(t).toFloat(), 12 + 9.5f * sin(t).toFloat()) } }
            "badge" -> { rect(3f, 7f, 17f, 21f, 4f); dot(18.5f, 5.5f, 3f) }
            "gesture" -> path { moveTo(3f, 17f); cubicTo(6f, 6f, 10f, 6f, 11.5f, 12f); cubicTo(13f, 18f, 17f, 18f, 21f, 7f) }
            "tabs" -> { rect(3f, 5f, 21f, 19f, 2f); line(3f, 9.5f, 21f, 9.5f); line(10f, 5f, 10f, 9.5f) }
            "archive" -> { rect(3f, 4f, 21f, 9f, 1.5f); poly(5f, 9f, 5f, 19f, 6f, 20f, 18f, 20f, 19f, 19f, 19f, 9f); line(10f, 13f, 14f, 13f) }
            "refresh" -> { arc(5f, 5f, 19f, 19f, 40f, 290f); poly(19.5f, 4f, 19f, 8.5f, 14.5f, 8f) }
            "shield" -> path { moveTo(12f, 3f); lineTo(19f, 6f); lineTo(19f, 11f); cubicTo(19f, 16f, 16f, 19.5f, 12f, 21f); cubicTo(8f, 19.5f, 5f, 16f, 5f, 11f); lineTo(5f, 6f); close() }
            "recents" -> { rect(4f, 8f, 16f, 20f, 2f); poly(8f, 4.5f, 18f, 4.5f, 19.5f, 6f, 19.5f, 16f) }
            "power" -> { arc(5f, 5f, 19f, 19f, 300f, 300f); line(12f, 3f, 12f, 11f) }
            "sparkle" -> path { moveTo(12f, 3f); quadTo(13f, 11f, 21f, 12f); quadTo(13f, 13f, 12f, 21f); quadTo(11f, 13f, 3f, 12f); quadTo(11f, 11f, 12f, 3f); close() }
            "play" -> poly(8f, 5f, 19f, 12f, 8f, 19f, close = true, filled = true)
            "pause" -> { rect(6.5f, 5f, 10.5f, 19f, 1f, filled = true); rect(13.5f, 5f, 17.5f, 19f, 1f, filled = true) }
            "prev" -> { poly(18f, 6f, 9f, 12f, 18f, 18f, close = true, filled = true); rect(6f, 6f, 8f, 18f, 1f, filled = true) }
            "next" -> { poly(6f, 6f, 15f, 12f, 6f, 18f, close = true, filled = true); rect(16f, 6f, 18f, 18f, 1f, filled = true) }
            "person" -> { circle(12f, 8f, 4f); arc(4.5f, 14f, 19.5f, 28f, 180f, 180f) }
            "globe" -> { circle(12f, 12f, 9f); c.drawOval(RectF(8f, 3f, 16f, 21f), paint.apply { style = Paint.Style.STROKE }); line(3f, 12f, 21f, 12f) }
            else -> dot(12f, 12f, 4f)
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Required override") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
