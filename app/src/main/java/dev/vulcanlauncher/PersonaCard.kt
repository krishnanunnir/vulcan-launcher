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

/** A collectible portrait of a contribution, drawn with the same native artwork on screen and in the PNG. */
internal class PersonaCard(context: Context, private val persona: Persona) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val artwork = PersonaArtwork(persona)
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
        artwork.draw(c, 180f, 225f)
        c.restore()
        block(c, context.getString(persona.tagline), 24f, 345f, 18f, INK, 2)
        block(c, context.getString(persona.role), 24f, 399f, 12f, SECONDARY, 2)
        paint.color = RULE; paint.strokeWidth = .8f; paint.strokeCap = Paint.Cap.ROUND; c.drawLine(24f, 425f, 336f, 425f, paint)
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
}
