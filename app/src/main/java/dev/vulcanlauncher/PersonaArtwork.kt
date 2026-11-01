package dev.vulcanlauncher

import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.math.min
import kotlin.math.roundToInt

private const val PAPER = 0xFFE9E7E2.toInt()
private const val INK = 0xFF2A2A28.toInt()
private const val SECONDARY = 0xFF595752.toInt()
private const val RULE = 0xFFC9C6BF.toInt()
private const val PERSONA_SIGNAL = 0xFFE8721C.toInt()

/** The same native character drawing powers the list portrait, profile scene and exported card. */
internal class PersonaArtwork(private val persona: Persona, private val dark: Boolean = false) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clothes get() = if (dark) 0xFFA9A69F.toInt() else INK
    fun portrait(c: Canvas, bounds: RectF) {
        val scale = min(bounds.width(), bounds.height()) / 100f
        c.save()
        c.clipPath(Path().apply { addOval(bounds, Path.Direction.CW) })
        draw(c, bounds.centerX(), bounds.centerY() + 28f * scale, scale, portraitOnly = true)
        c.restore()
    }
    fun draw(c: Canvas, x: Float, y: Float, scale: Float = 1f, portraitOnly: Boolean = false) {
        fun rect(l: Float, t: Float, r: Float, b: Float, color: Int, radius: Float = 3f) {
            paint.style = Paint.Style.FILL; paint.color = color; c.drawRoundRect(l, t, r, b, radius, radius, paint)
        }
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int = INK, width: Float = 7f) {
            paint.style = Paint.Style.STROKE; paint.color = color; paint.strokeWidth = width; paint.strokeCap = Paint.Cap.ROUND
            c.drawLine(x1, y1, x2, y2, paint); paint.style = Paint.Style.FILL
        }
        c.save(); c.translate(x, y); c.scale(scale, scale)
        paint.color = if (dark) 0xFF4A4945.toInt() else RULE; c.drawOval(-45f, 78f, 45f, 86f, paint)
        line(-12f, 47f, -17f, 76f, clothes, 12f); line(12f, 47f, 17f, 76f, clothes, 12f)
        rect(-31f, 73f, -9f, 81f, SECONDARY, 4f); rect(9f, 73f, 31f, 81f, SECONDARY, 4f)
        rect(-27f, -4f, 27f, 55f, clothes, 15f)
        rect(-9f, -8f, 9f, 4f, PAPER, 4f)
        line(-23f, 9f, -39f, 32f, clothes); line(23f, 9f, 39f, 30f, clothes)
        paint.color = PAPER; c.drawCircle(-39f, 32f, 6f, paint); c.drawCircle(39f, 30f, 6f, paint)
        paint.color = PAPER; c.drawCircle(0f, -34f, 28f, paint)
        // A friendly illustrated face; the hat and props reflect the person's contribution.
        paint.color = INK; c.drawCircle(-9f, -34f, 2.2f, paint); c.drawCircle(9f, -34f, 2.2f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.7f; paint.strokeCap = Paint.Cap.ROUND
        c.drawArc(-7f, -31f, 7f, -20f, 15f, 150f, false, paint); paint.style = Paint.Style.FILL
        paint.color = if (dark) INK else PAPER; paint.textAlign = Paint.Align.CENTER; paint.textSize = 13f; paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        if (!portraitOnly) c.drawText(initials(persona.name), 0f, 25f, paint)
        paint.color = PERSONA_SIGNAL; c.drawCircle(0f, 38f, 2.5f, paint)
        when (persona.hat) {
            Hat.HARD_HAT -> {
                paint.color = SECONDARY; c.drawArc(-29f, -70f, 29f, -19f, 180f, 180f, true, paint)
                rect(-33f, -47f, 33f, -41f, SECONDARY)
                rect(-3f, -71f, 3f, -47f, PAPER)
                if (!portraitOnly) {
                    // A blueprint in one hand and blocks waiting to become a home screen.
                    rect(-78f, 5f, -43f, 44f, PAPER, 4f)
                    paint.color = SECONDARY; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f
                    c.drawRect(-71f, 13f, -51f, 34f, paint); c.drawLine(-61f, 13f, -61f, 34f, paint); c.drawLine(-71f, 23f, -51f, 23f, paint); paint.style = Paint.Style.FILL
                    rect(57f, 55f, 105f, 79f, SECONDARY)
                    rect(61f, 26f, 85f, 50f, PAPER)
                    rect(90f, 26f, 114f, 50f, PAPER)
                    rect(67f, -3f, 91f, 21f, PAPER)
                }
            }
            Hat.SPEECH_BUBBLE -> {
                paint.color = INK; c.drawArc(-28f, -65f, 28f, -19f, 185f, 170f, true, paint)
                if (!portraitOnly) {
                    // ISO language codes keep the visual tied to the translations this contributor made.
                    bubble(c, -102f, -53f, "DE")
                    bubble(c, 52f, -20f, "RU")
                }
            }
            Hat.MAGNIFIER -> {
                paint.color = SECONDARY; c.drawArc(-28f, -65f, 28f, -19f, 185f, 170f, true, paint)
                if (!portraitOnly) {
                    // A magnifying glass and checked report for the bugs caught on a real device.
                    paint.color = PAPER; c.drawCircle(-76f, -12f, 22f, paint)
                    paint.color = INK; paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
                    c.drawCircle(-76f, -12f, 22f, paint); paint.style = Paint.Style.FILL
                    line(-61f, 5f, -44f, 25f, SECONDARY, 6f)
                    paint.color = INK; c.drawOval(-81f, -20f, -71f, -5f, paint)
                    line(-86f, -18f, -66f, -7f, INK, 1.5f)
                    line(-86f, -7f, -66f, -18f, INK, 1.5f)
                    rect(55f, -28f, 104f, 45f, PAPER, 5f)
                    rect(68f, -32f, 91f, -23f, SECONDARY)
                    for (y in listOf(-9f, 9f, 27f)) {
                        line(63f, y, 67f, y + 4, INK, 1.5f); line(67f, y + 4, 73f, y - 3, INK, 1.5f)
                        line(80f, y, 96f, y, SECONDARY, 1.5f)
                    }
                }
            }
            Hat.RULER -> {
                paint.color = INK; c.drawArc(-28f, -65f, 28f, -19f, 185f, 170f, true, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f
                c.drawCircle(-10f, -34f, 9f, paint); c.drawCircle(10f, -34f, 9f, paint)
                c.drawLine(-1f, -34f, 1f, -34f, paint); paint.style = Paint.Style.FILL
                if (!portraitOnly) {
                    // A ruler and a phone: the details that make a home screen feel right.
                    rect(-89f, -13f, -61f, 58f, PAPER)
                    for (i in 0..6) line(-85f, -5f + i * 9, if (i % 2 == 0) -73f else -78f, -5f + i * 9, SECONDARY, 1f)
                    rect(58f, -37f, 103f, 43f, INK, 7f)
                    rect(62f, -29f, 99f, 31f, PAPER, 3f)
                    for (x in listOf(70f, 88f)) for (y in listOf(-17f, 0f, 17f)) rect(x - 4, y - 4, x + 4, y + 4, SECONDARY, 2f)
                    line(75f, 37f, 86f, 37f, PAPER, 2f)
                }
            }
        }
        c.restore()
    }
    private fun bubble(c: Canvas, x: Float, y: Float, code: String) {
        paint.color = PAPER; c.drawRoundRect(x, y, x + 54f, y + 34f, 10f, 10f, paint)
        val path = Path().apply { moveTo(x + 14, y + 31); lineTo(x + 14, y + 43); lineTo(x + 26, y + 31); close() }
        c.drawPath(path, paint)
        paint.color = INK; paint.textSize = 16f; paint.textAlign = Paint.Align.CENTER; paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        c.drawText(code, x + 27, y + 23, paint)
    }
}

internal class PersonaFace(persona: Persona, dark: Boolean, private val size: Int) : Drawable() {
    private val artwork = PersonaArtwork(persona, dark)
    private var opacity = 255
    override fun draw(canvas: Canvas) {
        val region = RectF(bounds)
        val save = if (opacity < 255) canvas.saveLayerAlpha(region, opacity) else canvas.save()
        artwork.portrait(canvas, region)
        canvas.restoreToCount(save)
    }
    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Required by Drawable")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

internal class PersonaScene(private val activity: VulcanActivity, persona: Persona) : View(activity) {
    private val artwork = PersonaArtwork(persona, activity.m3.dark)
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = activity.m3.outlineVariant; strokeWidth = .6f; alpha = 100 }
    init { background = activity.well(24 * activity.dp); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) (312 * activity.dp).roundToInt() else MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, resolveSize((width * 190f / 312f).roundToInt(), heightMeasureSpec))
    }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val scale = min(width / 312f, height / 190f)
        c.save(); c.translate((width - 312f * scale) / 2, (height - 190f * scale) / 2); c.scale(scale, scale)
        c.clipRoundRectCompat(2f, 2f, 310f, 188f, 22f)
        for (i in 0..12) c.drawLine(12f + i * 24f, 8f, 12f + i * 24f, 184f, grid)
        for (i in 0..7) c.drawLine(8f, 10f + i * 24f, 304f, 10f + i * 24f, grid)
        artwork.draw(c, 156f, 91f)
        c.restore()
    }
}

private fun Canvas.clipRoundRectCompat(l: Float, t: Float, r: Float, b: Float, radius: Float) {
    clipPath(Path().apply { addRoundRect(l, t, r, b, radius, radius, Path.Direction.CW) })
}
