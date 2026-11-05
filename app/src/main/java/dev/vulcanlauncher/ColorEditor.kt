package dev.vulcanlauncher

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout

// Custom colours: one row per colour role (COLOR_ROLES), each opening a picker (swatches, HSV sliders, hex field). A colour is saved
// in looks as "c_<role>" and laid over the theme's palette; removing it brings the theme's colour back.

const val COLORS = "Colors"

fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

/** The custom colours page: a row per role showing its colour now, and a reset for all of them. */
fun SettingsActivity.colorsPage(list: LinearLayout) {
    val set = looks.colors()
    val rows = COLOR_ROLES.map { (key, title) ->
        listItem(null, getString(title), set[key]?.let(::hex) ?: getString(R.string.color_from_theme), trailing = dot(m3.role(key))) { editColor(key, title) }
    }
    list.addView(text(getString(R.string.custom_colors_info), 14f, m3.onSurfaceVariant).apply { setPadding((32 * dp).toInt(), (8 * dp).toInt(), (32 * dp).toInt(), (8 * dp).toInt()) })
    list.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        housing(this, 24 * dp)
        elevation = 2 * dp
        clipToOutline = true
        setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
        rows.forEach(::addView)
        if (set.isNotEmpty()) addView(listItem(null, getString(R.string.reset_colors), null) {
            looks.remove(COLOR_ROLES.map { "c_${it.first}" })
            changed()
        })
    }, LinearLayout.LayoutParams(-1, -2).apply { setMargins((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt()) })
}

private fun SettingsActivity.dot(color: Int) = View(this).apply {
    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setStroke(dp.toInt(), m3.outlineVariant) }
    layoutParams = LinearLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt())
}

/** Wallpaper tones and a few fixed hues, as Nova's swatches: rows of six from light to dark. */
private fun SettingsActivity.swatches(): List<List<Int>> {
    val tones = listOf(90, 80, 65, 50, 35, 20)
    val wallpaper = palette(this, darkPanels, "system").ramps.take(3)
    val hues = listOf(0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047, 0xFF1E88E5, 0xFF8E24AA).map { it.toInt() }
    return wallpaper.map { r -> tones.map(r) } + hues.map { h -> tones.map { tone(h, it, 1f, 1f) } } +
        listOf(listOf(100, 80, 60, 40, 20, 0).map { tone(0xFF808080.toInt(), it, 0f, 0f) })
}

/**
 * Picks [key]'s colour: swatches, hue/saturation/brightness sliders and a hex field, all kept in step, over a preview of
 * the scheme with the colour in it. Saving checks that text stays readable first.
 */
private fun SettingsActivity.editColor(key: String, title: Int) {
    val pad = (20 * dp).toInt()
    val hsv = FloatArray(3).also { Color.colorToHSV(m3.role(key), it) }
    var color = m3.role(key)
    var updating = false
    var saveButton: android.widget.Button? = null

    // The preview: a card on the background, with text, a divider, the accent and a well, in the colours being picked.
    val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad / 2 + 4, pad / 2, pad / 2 + 4, pad / 2) }
    val line1 = text(getString(R.string.color_preview_title), 16f)
    val line2 = text("", 13f)
    val rule = View(this)
    val button = text(getString(R.string.color_preview_button), 13f, medium = true).apply { setPadding(pad * 3 / 4, pad / 4, pad * 3 / 4, pad / 4) }
    val well = text(getString(R.string.color_preview_search), 13f).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(pad * 3 / 4, 0, 0, 0) }
    card.addView(line1); card.addView(line2)
    card.addView(rule, LinearLayout.LayoutParams(-1, maxOf(1, dp.toInt())).apply { setMargins(0, pad / 2, 0, pad / 2) })
    card.addView(button, LinearLayout.LayoutParams(-2, -2))
    val preview = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
        addView(card)
        addView(well, LinearLayout.LayoutParams(-1, (36 * dp).toInt()).apply { topMargin = pad / 2 })
    }
    fun showPreview() {
        val p = m3.with(mapOf(key to color))
        preview.background = rounded(p.surface, 16 * dp, m3.outlineVariant)
        card.background = rounded(p.raised, 12 * dp)
        line1.setTextColor(p.onSurface)
        line2.setTextColor(p.onSurfaceVariant)
        val low = p.lowContrast()
        line2.text = if (low != null) getString(R.string.color_contrast_low, String.format("%.1f", low.first))
            else getString(R.string.color_contrast_ok, String.format("%.1f", p.textContrast()))
        rule.setBackgroundColor(p.outlineVariant)
        button.background = rounded(p.signal, 50 * dp)
        button.setTextColor(onColor(p.signal))
        well.background = rounded(p.well, 18 * dp)
        well.setTextColor(p.onSurfaceVariant)
    }

    val rings = ArrayList<Pair<View, Int>>()
    val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val field = EditText(this).apply { setSingleLine(); setTextColor(m3.onSurface); hint = "#RRGGBB" }
    class Slider(val name: Int, val limit: Int, val unit: String, val get: () -> Int, val set: (Int) -> Unit) {
        val label = text("", 14f)
        val bar = android.widget.SeekBar(this@editColor).apply { max = limit; contentDescription = getString(name) }
        val track = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0, 0)).apply { cornerRadius = 4 * dp }
    }
    lateinit var update: (Int?, Boolean) -> Unit
    val sliders = listOf(
        Slider(R.string.hsv_hue, 359, "°", { hsv[0].toInt() }, { hsv[0] = it.toFloat() }),
        Slider(R.string.hsv_saturation, 100, "%", { (hsv[1] * 100).toInt() }, { hsv[1] = it / 100f }),
        Slider(R.string.hsv_brightness, 100, "%", { (hsv[2] * 100).toInt() }, { hsv[2] = it / 100f }),
    )
    sliders[0].track.colors = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f % 360, 1f, 1f)) }

    /** Takes [c] (or the sliders' HSV when null) as the colour and brings every control but the hex field being typed in up to date. */
    update = { c, fromField ->
        if (c != null) { color = c; Color.colorToHSV(c, hsv) } else color = Color.HSVToColor(hsv)
        updating = true
        for (s in sliders) { s.bar.progress = s.get(); s.label.text = "${getString(s.name)}  ${s.get()}${s.unit}" }
        sliders[1].track.colors = intArrayOf(Color.HSVToColor(floatArrayOf(hsv[0], 0f, hsv[2])), Color.HSVToColor(floatArrayOf(hsv[0], 1f, hsv[2])))
        sliders[2].track.colors = intArrayOf(Color.BLACK, Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f)))
        if (!fromField) field.setText(hex(color))
        for ((v, sc) in rings) (v.background as GradientDrawable).setStroke((if (hex(sc) == hex(color)) 3 * dp else dp).toInt(),
            if (hex(sc) == hex(color)) m3.onSurface else m3.outlineVariant)
        saveButton?.isEnabled = true
        updating = false
        showPreview()
    }

    for (row in swatches()) grid.addView(LinearLayout(this).apply {
        // Square cells: a circle as wide as its share of the row.
        for (sc in row) addView(object : View(context) { override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w) }.apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(sc) }
            contentDescription = hex(sc)
            setOnClickListener { update(sc, false) }
            rings += this to sc
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins((3 * dp).toInt(), (3 * dp).toInt(), (3 * dp).toInt(), (3 * dp).toInt()) })
    })

    val controls = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(grid)
        for (s in sliders) {
            addView(s.label.apply { setPadding(0, pad / 2, 0, 0) })
            s.bar.progressDrawable = android.graphics.drawable.LayerDrawable(arrayOf(s.track)).apply {
                setLayerGravity(0, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL); setLayerHeight(0, (8 * dp).toInt())
            }
            s.bar.thumbTintList = android.content.res.ColorStateList.valueOf(m3.onSurface)
            s.bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: android.widget.SeekBar, v: Int, user: Boolean) { if (!updating) { s.set(v); update(null, false) } }
                override fun onStartTrackingTouch(b: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(b: android.widget.SeekBar) {}
            })
            fun step(sign: Int, label: Int) = text(if (sign < 0) "−" else "+", 22f).apply {
                gravity = Gravity.CENTER
                background = pressable(null, 20 * dp)
                contentDescription = getString(label, getString(s.name))
                setOnClickListener { s.set((s.get() + sign).coerceIn(0, s.limit)); update(null, false) }
            }
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(step(-1, R.string.step_less), LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()))
                addView(s.bar, LinearLayout.LayoutParams(0, (40 * dp).toInt(), 1f))
                addView(step(1, R.string.step_more), LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()))
            })
        }
        addView(field)
    }
    field.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable) {
            if (updating) return
            val c = Regex("#?[0-9a-fA-F]{6}").matchEntire(s.trim())?.let { Color.parseColor("#" + it.value.removePrefix("#")) }
            if (c == null) { saveButton?.isEnabled = false; return } // save only what the field shows
            if (hex(c) != hex(color)) update(c, true) else saveButton?.isEnabled = true
        }
    })
    update(color, false)

    // The controls scroll beside a fixed preview: under it in portrait, next to it in landscape, where there's no height
    // for both. Capped so the preview and buttons stay on screen.
    val wide = resources.displayMetrics.run { widthPixels > heightPixels }
    val scroll = object : android.widget.ScrollView(this) {
        override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(
            (resources.displayMetrics.heightPixels * if (wide) 0.5f else 0.4f).toInt(), MeasureSpec.AT_MOST))
    }.apply { addView(controls) }
    val box = LinearLayout(this).apply {
        orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        setPadding(pad, pad / 2, pad, 0)
        if (wide) {
            addView(preview, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = pad })
            addView(scroll, LinearLayout.LayoutParams(0, -2, 1f))
        } else {
            addView(preview, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = pad / 2 })
            addView(scroll)
        }
    }
    fun save(c: Int) { looks["c_$key"] = hex(c); changed() }
    sheet().setTitle(title).setView(box)
        .setPositiveButton(R.string.save) { _, _ ->
            val c = color
            // Unchanged: keep following the theme rather than pinning its current colour.
            if (hex(c) == hex(m3.role(key))) return@setPositiveButton
            val low = m3.with(mapOf(key to c)).lowContrast()
            if (low == null) save(c)
            else dialog().setTitle(R.string.low_contrast).setMessage(getString(R.string.low_contrast_info, String.format("%.1f", low.first), String.format("%.1f", low.second)))
                .setPositiveButton(R.string.save_anyway) { _, _ -> save(c) }
                .setNegativeButton(R.string.cancel, null).show()
        }
        .setNeutralButton(R.string.reset) { _, _ -> looks.remove(listOf("c_$key")); changed() }
        .setNegativeButton(R.string.cancel, null).show()
        .also { saveButton = it.getButton(android.app.AlertDialog.BUTTON_POSITIVE) }
}
