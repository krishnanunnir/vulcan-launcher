package dev.vulcanlauncher

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import kotlin.math.max
import kotlin.math.roundToInt

/** Native, scalable biography around the artwork; the share control stays under the thumb. */
internal class PersonaProfile(private val activity: SettingsActivity, val persona: Persona, close: () -> Unit) : LinearLayout(activity) {
    private val dp = activity.dp
    internal val card = PersonaCard(activity, persona)
    private fun px(value: Int) = (value * dp).roundToInt()
    init {
        orientation = VERTICAL
        setBackgroundColor(activity.m3.surface)
        val header = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(px(4), 0, px(16), 0)
            addView(activity.iconButton("back", activity.getString(R.string.persona_close), activity.m3.onSurface) { close() })
            addView(activity.text(activity.getString(R.string.contributors), 22f).apply { setPadding(px(8), 0, 0, 0) }, LayoutParams(0, -2, 1f))
        }
        addView(header, LayoutParams(-1, px(64)))
        val scroll = ScrollView(activity).apply {
            isFillViewport = true; isVerticalScrollBarEnabled = false
            addView(ProfileContent(), FrameLayout.LayoutParams(-1, -2))
        }
        addView(FrameLayout(activity).apply {
            addView(scroll, FrameLayout.LayoutParams(minOf(resources.displayMetrics.widthPixels, px(640)), -1, Gravity.CENTER_HORIZONTAL))
        }, LayoutParams(-1, 0, 1f))
        addView(LinearLayout(activity).apply {
            orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(24), px(12), px(24), px(16))
            addView(Button(activity).apply {
                tag = "persona/share"
                setText(R.string.persona_share); textSize = 16f; isAllCaps = false
                setTextColor(activity.m3.surface); minimumHeight = px(56)
                setPadding(px(24), px(12), px(24), px(12))
                background = activity.pressable(activity.rounded(activity.m3.onSurface, 16 * dp), 16 * dp, activity.m3.surface)
                setOnClickListener { share() }
            }, LayoutParams(minOf(resources.displayMetrics.widthPixels - px(48), px(592)), -2))
        }, LayoutParams(-1, -2))
        @Suppress("DEPRECATION")
        setOnApplyWindowInsetsListener { _, insets ->
            val cutout = if (android.os.Build.VERSION.SDK_INT >= 28) insets.displayCutout else null
            setPadding(max(insets.systemWindowInsetLeft, cutout?.safeInsetLeft ?: 0), max(insets.systemWindowInsetTop, cutout?.safeInsetTop ?: 0),
                max(insets.systemWindowInsetRight, cutout?.safeInsetRight ?: 0), insets.systemWindowInsetBottom)
            insets
        }
    }

    private inner class ProfileContent : LinearLayout(activity) {
        private var wide: Boolean? = null
        private val identity = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(activity.personaFace(persona, 64), LayoutParams(px(64), px(64)).apply { marginEnd = px(16) })
            addView(LinearLayout(activity).apply {
                orientation = VERTICAL
                addView(activity.text(persona.name, 22f))
                addView(activity.text(activity.getString(persona.title), 16f).apply { setPadding(0, px(4), 0, 0) })
                addView(activity.text(activity.getString(persona.role), 14f, activity.m3.onSurfaceVariant).apply { setPadding(0, px(8), 0, 0); setLineSpacing(dp, 1f) })
            }, LayoutParams(0, -2, 1f))
        }
        private val scene = PersonaScene(activity, persona)
        private val description = LinearLayout(activity).apply {
            orientation = VERTICAL
            addView(activity.text(activity.getString(persona.tagline), 22f).apply { setLineSpacing(2 * dp, 1f) })
            addView(activity.text(activity.getString(persona.signature), 14f, activity.m3.onSurfaceVariant).apply { setPadding(0, px(12), 0, 0); setLineSpacing(2 * dp, 1f) })
        }
        init { setPadding(px(24), px(16), px(24), px(24)) }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val expanded = MeasureSpec.getSize(widthMeasureSpec) / dp >= 600
            if (expanded != wide) {
                wide = expanded
                listOf(identity, scene, description).forEach { (it.parent as? ViewGroup)?.removeView(it) }
                removeAllViews()
                if (expanded) {
                    orientation = HORIZONTAL; gravity = Gravity.TOP
                    addView(LinearLayout(activity).apply {
                        orientation = VERTICAL
                        addView(identity, LayoutParams(-1, -2))
                        addView(description, LayoutParams(-1, -2).apply { topMargin = px(24) })
                    }, LayoutParams(0, -2, 1f).apply { marginEnd = px(24) })
                    addView(scene, LayoutParams(0, -2, .9f))
                } else {
                    orientation = VERTICAL
                    addView(identity, LayoutParams(-1, -2))
                    addView(scene, LayoutParams(-1, -2).apply { topMargin = px(24) })
                    addView(description, LayoutParams(-1, -2).apply { topMargin = px(24) })
                }
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
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
