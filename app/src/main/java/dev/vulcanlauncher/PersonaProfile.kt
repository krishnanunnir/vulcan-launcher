package dev.vulcanlauncher

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.view.Gravity
import android.widget.*
import kotlin.math.max
import kotlin.math.roundToInt

/** A contributor's character card, with the same artwork on screen and in the shared image. */
internal class PersonaProfile(private val activity: SettingsActivity, val persona: Persona, close: () -> Unit) : FrameLayout(activity) {
    private val dp = activity.dp
    internal val card = PersonaCard(activity, persona)
    private fun px(value: Int) = (value * dp).roundToInt()
    init {
        setBackgroundColor(activity.m3.surface)
        val header = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(px(16), 0, px(16), 0)
            addView(TextView(activity).apply {
                setText(R.string.contributors); textSize = 18f; setTextColor(activity.m3.onSurface)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button(R.string.persona_close) { close() }, LinearLayout.LayoutParams(px(88), px(48)))
        }
        addView(header, LayoutParams(-1, px(64), Gravity.TOP))
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(24), px(12), px(24), px(24))
            addView(card, LinearLayout.LayoutParams(-1, px(430)))
            addView(TextView(activity).apply {
                text = activity.getString(persona.signature); textSize = 17f; setTextColor(activity.m3.onSurface)
                gravity = Gravity.CENTER; setPadding(0, px(24), 0, px(24)); typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(-1, -2))
            addView(button(R.string.persona_share) { share() }, LinearLayout.LayoutParams(-1, px(56)))
        }
        val scroll = ScrollView(activity).apply {
            isFillViewport = true; addView(content, LayoutParams(-1, -2))
        }
        val wrapper = FrameLayout(activity).apply {
            addView(scroll, LayoutParams(minOf(resources.displayMetrics.widthPixels, px(460)), -1, Gravity.CENTER_HORIZONTAL))
        }
        addView(wrapper, LayoutParams(-1, -1).apply { topMargin = px(64) })
        @Suppress("DEPRECATION")
        setOnApplyWindowInsetsListener { _, insets ->
            val cutout = if (android.os.Build.VERSION.SDK_INT >= 28) insets.displayCutout else null
            setPadding(max(insets.systemWindowInsetLeft, cutout?.safeInsetLeft ?: 0), max(insets.systemWindowInsetTop, cutout?.safeInsetTop ?: 0),
                max(insets.systemWindowInsetRight, cutout?.safeInsetRight ?: 0), insets.systemWindowInsetBottom)
            insets
        }
    }
    private fun button(title: Int, click: () -> Unit) = Button(activity).apply {
        setText(title); textSize = 15f; isAllCaps = false; setTextColor(activity.m3.onSurface)
        background = activity.rounded(activity.m3.surfaceContainer, 16 * dp)
        setOnClickListener { click() }
        foreground = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x24000000), null, activity.rounded(-1, 16 * dp))
    }
    private fun share() {
        try {
            val bitmap = Bitmap.createBitmap(1080, 1350, Bitmap.Config.ARGB_8888)
            val uri = try { card.render(Canvas(bitmap), 1080f, 1350f); PersonaCardProvider.save(activity, bitmap) } finally { bitmap.recycle() }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, activity.getString(R.string.persona_share_text, persona.name, activity.getString(persona.title), activity.getString(persona.tagline)))
                clipData = ClipData.newUri(activity.contentResolver, "", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(send, activity.getString(R.string.persona_share)))
        } catch (_: Exception) { activity.toast(R.string.persona_share_failed) }
    }
}
