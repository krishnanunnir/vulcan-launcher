package dev.vulcanlauncher

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import kotlin.math.min

private const val PAPER = 0xFFE9E7E2.toInt()
private const val WELL = 0xFFDEDBD5.toInt()
private const val INK = 0xFF2A2A28.toInt()
private const val SECONDARY = 0xFF595752.toInt()
private const val RULE = 0xFFC9C6BF.toInt()
private const val PERSONA_SIGNAL = 0xFFE8721C.toInt()

/** A collectible portrait of a contribution, drawn with the same native artwork on screen and in the PNG. */
internal class PersonaCard(context: Context, private val persona: Persona) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init { contentDescription = context.getString(R.string.persona_card_description, persona.name, context.getString(persona.title), context.getString(persona.tagline)) }
    override fun onDraw(c: Canvas) = render(c, width.toFloat(), height.toFloat())
    fun render(c: Canvas, w: Float, h: Float) {
        val s = min(w / 360f, h / 450f)
        c.save(); c.translate((w - 360f * s) / 2, (h - 450f * s) / 2); c.scale(s, s)
        paint.color = PAPER; paint.style = Paint.Style.FILL
        c.drawRoundRect(0f, 0f, 360f, 450f, 22f, 22f, paint)
        text(c, context.getString(R.string.persona_card_brand), 24f, 34f, 13f, SECONDARY)
        text(c, context.getString(persona.title), 24f, 83f, 34f)
        text(c, persona.name, 24f, 112f, 17f, SECONDARY)
        paint.color = WELL; c.drawRoundRect(24f, 134f, 336f, 324f, 18f, 18f, paint)
        c.save(); c.clipRect(26f, 136f, 334f, 322f)
        paint.color = RULE; paint.strokeWidth = .6f
        for (i in 0..12) c.drawLine(36f + i * 24f, 140f, 36f + i * 24f, 320f, paint)
        for (i in 0..7) c.drawLine(26f, 144f + i * 24f, 334f, 144f + i * 24f, paint)
        character(c)
        c.restore()
        block(c, context.getString(persona.tagline), 24f, 345f, 18f, INK, 2)
        block(c, context.getString(persona.role), 24f, 399f, 12f, SECONDARY, 2)
        paint.color = RULE; paint.strokeWidth = .8f; c.drawLine(24f, 425f, 336f, 425f, paint)
        text(c, context.getString(R.string.persona_card_footer), 24f, 440f, 10f, SECONDARY)
        c.restore()
    }
    private fun text(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int = INK) {
        paint.color = color; paint.textSize = size; paint.textAlign = Paint.Align.LEFT
        paint.typeface = Typeface.create(if (size >= 30) "sans-serif-light" else "sans-serif", Typeface.NORMAL)
        if (size >= 30) while (paint.textSize > 24 && paint.measureText(value) > 312) paint.textSize -= 1
        c.drawText(TextUtils.ellipsize(value, TextPaint(paint), 312f, TextUtils.TruncateAt.END).toString(), x, y, paint)
    }
    private fun block(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, maxLines: Int) {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = size; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
        val layout = StaticLayout.Builder.obtain(value, 0, value.length, p, 312)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END).build()
        c.save(); c.translate(x, y); layout.draw(c); c.restore()
    }
    private fun character(c: Canvas) {
        fun rect(l: Float, t: Float, r: Float, b: Float, color: Int, radius: Float = 3f) {
            paint.style = Paint.Style.FILL; paint.color = color; c.drawRoundRect(l, t, r, b, radius, radius, paint)
        }
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int = INK, width: Float = 7f) {
            paint.style = Paint.Style.STROKE; paint.color = color; paint.strokeWidth = width; paint.strokeCap = Paint.Cap.ROUND
            c.drawLine(x1, y1, x2, y2, paint); paint.style = Paint.Style.FILL
        }
        c.save(); c.translate(180f, 225f)
        paint.color = RULE; c.drawOval(-45f, 78f, 45f, 86f, paint)
        line(-12f, 47f, -17f, 76f, INK, 12f); line(12f, 47f, 17f, 76f, INK, 12f)
        rect(-31f, 73f, -9f, 81f, SECONDARY, 4f); rect(9f, 73f, 31f, 81f, SECONDARY, 4f)
        rect(-27f, -4f, 27f, 55f, INK, 15f)
        rect(-9f, -8f, 9f, 4f, PAPER, 4f)
        line(-23f, 9f, -39f, 32f); line(23f, 9f, 39f, 30f)
        paint.color = PAPER; c.drawCircle(-39f, 32f, 6f, paint); c.drawCircle(39f, 30f, 6f, paint)
        paint.color = PAPER; c.drawCircle(0f, -34f, 28f, paint)
        // A friendly illustrated face; the hat and props reflect the person's contribution.
        paint.color = INK; c.drawCircle(-9f, -34f, 2.2f, paint); c.drawCircle(9f, -34f, 2.2f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.7f; paint.strokeCap = Paint.Cap.ROUND
        c.drawArc(-7f, -31f, 7f, -20f, 15f, 150f, false, paint); paint.style = Paint.Style.FILL
        paint.color = PAPER; paint.textAlign = Paint.Align.CENTER; paint.textSize = 13f; paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        c.drawText(initials(persona.name), 0f, 25f, paint)
        paint.color = PERSONA_SIGNAL; c.drawCircle(0f, 38f, 2.5f, paint)
        when (persona.hat) {
            Hat.HARD_HAT -> {
                paint.color = SECONDARY; c.drawArc(-29f, -70f, 29f, -19f, 180f, 180f, true, paint)
                rect(-33f, -47f, 33f, -41f, SECONDARY)
                rect(-3f, -71f, 3f, -47f, PAPER)
                // A blueprint in one hand and blocks waiting to become a home screen.
                rect(-78f, 5f, -43f, 44f, PAPER, 4f)
                paint.color = SECONDARY; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f
                c.drawRect(-71f, 13f, -51f, 34f, paint); c.drawLine(-61f, 13f, -61f, 34f, paint); c.drawLine(-71f, 23f, -51f, 23f, paint); paint.style = Paint.Style.FILL
                rect(57f, 55f, 105f, 79f, SECONDARY)
                rect(61f, 26f, 85f, 50f, PAPER)
                rect(90f, 26f, 114f, 50f, PAPER)
                rect(67f, -3f, 91f, 21f, PAPER)
            }
            Hat.SPEECH_BUBBLE -> {
                paint.color = INK; c.drawArc(-28f, -65f, 28f, -19f, 185f, 170f, true, paint)
                // ISO language codes keep the visual tied to the translations this contributor made.
                bubble(c, -102f, -53f, "DE")
                bubble(c, 52f, -20f, "RU")
            }
            Hat.MAGNIFIER -> {
                paint.color = SECONDARY; c.drawArc(-28f, -65f, 28f, -19f, 185f, 170f, true, paint)
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
            Hat.RULER -> {
                paint.color = INK; c.drawArc(-28f, -65f, 28f, -19f, 185f, 170f, true, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f
                c.drawCircle(-10f, -34f, 9f, paint); c.drawCircle(10f, -34f, 9f, paint)
                c.drawLine(-1f, -34f, 1f, -34f, paint); paint.style = Paint.Style.FILL
                // A ruler and a phone: the details that make a home screen feel right.
                rect(-89f, -13f, -61f, 58f, PAPER)
                for (i in 0..6) line(-85f, -5f + i * 9, if (i % 2 == 0) -73f else -78f, -5f + i * 9, SECONDARY, 1f)
                rect(58f, -37f, 103f, 43f, INK, 7f)
                rect(62f, -29f, 99f, 31f, PAPER, 3f)
                for (x in listOf(70f, 88f)) for (y in listOf(-17f, 0f, 17f)) rect(x - 4, y - 4, x + 4, y + 4, SECONDARY, 2f)
                line(75f, 37f, 86f, 37f, PAPER, 2f)
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
