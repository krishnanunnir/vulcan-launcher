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
    // The settings swatches' ring: the outline vanished on a dark card and matched the Dividers colour.
    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setStroke((1.5f * dp).toInt(), m3.onSurfaceVariant) }
    layoutParams = LinearLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt())
}

/** Wallpaper tones and a few fixed hues, as Nova's swatches: rows of six from light to dark. */
private fun VulcanActivity.swatches(): List<List<Int>> {
    val tones = listOf(90, 80, 65, 50, 35, 20)
    val wallpaper = palette(this, darkPanels, "system").ramps.take(3)
    val hues = listOf(0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047, 0xFF1E88E5, 0xFF8E24AA).map { it.toInt() }
    return wallpaper.map { r -> tones.map(r) } + hues.map { h -> tones.map { tone(h, it, 1f, 1f) } } +
        listOf(listOf(100, 80, 60, 40, 20, 0).map { tone(0xFF808080.toInt(), it, 0f, 0f) })
}

/** The theme's colour for role [key], ignoring any custom colour set for it. */
private fun SettingsActivity.themeRole(key: String) = palette(this, darkPanels, looks.str("accent"), looks.colors() - key).role(key)

/**
 * The scheme sample: a small home screen on a wallpaper washed with the accent, painted from a palette. App icons are raised
 * keys; a long-press menu shows the card, text, secondary text and divider; the page dot the accent; the dock the background
 * with a small search slot (the well) at its end. Text is drawn as bars, so nothing in it looks like it can be tapped or typed in.
 */
private fun VulcanActivity.schemePreview(): Pair<View, (Palette) -> Unit> {
    val pad = (12 * dp).toInt()
    fun px(v: Int) = (v * dp).toInt()
    fun bar(w: Int, h: Float = 4f) = View(this).apply { layoutParams = LinearLayout.LayoutParams(px(w), (h * dp).toInt()) }
    fun key(size: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(px(size), px(size)) }
    fun dot(size: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(px(size), px(size)) }
    // Home row: four app keys, each with its name as a bar under it.
    val keys = List(4) { key(36) }
    val names = List(4) { bar(26, 3f).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = px(5) } }
    val apps = LinearLayout(this).apply {
        keys.zip(names).forEach { (k, n) ->
            addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; addView(k); addView(n) },
                LinearLayout.LayoutParams(0, -2, 1f))
        }
    }
    // The menu: rows with an icon well and a name, a hairline under the first.
    val wells = List(3) { dot(18) }
    val lines = listOf(bar(80), bar(56), bar(68))
    val rule = View(this)
    val menu = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, pad / 3, 0, pad / 3)
        wells.zip(lines).forEachIndexed { i, (w, l) ->
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(pad, pad / 2, pad, pad / 2)
                addView(w); addView(l.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = px(10) })
            })
            if (i == 0) addView(rule, LinearLayout.LayoutParams(-1, maxOf(1, (dp).toInt())).apply { setMargins(pad, 0, pad, 0) })
        }
    }
    val dots = List(3) { dot(6) }
    val dockKeys = List(3) { key(30) }
    val slot = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.CENTER }
    val dock = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(10), px(6), px(6), px(6))
        dockKeys.forEach { addView(it, (it.layoutParams as LinearLayout.LayoutParams).apply { marginEnd = px(10) }) }
        addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        addView(slot, LinearLayout.LayoutParams(px(64), px(30)))
    }
    val wall = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad + pad / 3, pad, pad)
        addView(apps, LinearLayout.LayoutParams(-1, -2))
        addView(menu, LinearLayout.LayoutParams(px(176), -2).apply { setMargins(pad / 2, pad, 0, pad) })
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER
            dots.forEach { addView(it, (it.layoutParams as LinearLayout.LayoutParams).apply { setMargins(px(4), 0, px(4), 0) }) }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = pad * 2 / 3 })
        addView(dock, LinearLayout.LayoutParams(-1, -2))
    }
    fun oval(c: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
    fun paint(p: Palette) {
        // A stand-in wallpaper: the background washed with the accent, so picking a colour visibly tints the screen.
        wall.background = rounded(blend(p.surface, p.signal, 0.22f), 16 * dp)
        keys.forEach { it.background = rounded(p.raised, 11 * dp, p.outlineVariant, dp / 2) }
        names.forEach { it.background = rounded(blend(p.onSurface, 0, 0.45f), 2 * dp) }
        menu.background = rounded(p.raised, 14 * dp, p.outlineVariant, dp / 2)
        wells.forEach { it.background = oval(p.well) }
        lines[0].background = rounded(p.onSurface, 2 * dp); lines[2].background = rounded(p.onSurface, 2 * dp)
        lines[1].background = rounded(p.onSurfaceVariant, 2 * dp)
        rule.setBackgroundColor(p.outlineVariant)
        dots.forEachIndexed { i, d -> d.background = oval(if (i == 0) p.signal else blend(p.onSurfaceVariant, 0, 0.5f)) }
        dock.background = rounded(p.surface, 21 * dp)
        // Keys stand out from the dock by their rim, as the dock is about their own colour.
        dockKeys.forEach { it.background = rounded(p.raised, 9 * dp, p.outlineVariant, dp) }
        slot.background = rounded(p.well, 15 * dp)
        slot.setImageDrawable(symbol("search", p.onSurfaceVariant, 16f))
    }
    return wall to ::paint
}

/** Picks [key]'s role colour, over a preview of the scheme with the colour in it. Saving checks that text stays readable first. */
private fun SettingsActivity.editColor(key: String, title: Int) {
    val (preview, paintScheme) = schemePreview()
    val theme = themeRole(key)
    colorSheet(title, m3.role(key), key !in looks.colors(), theme, R.string.color_from_theme, preview, { paintScheme(m3.with(mapOf(key to it))) }) { c ->
        fun save(c: Int) { looks["c_$key"] = hex(c); changed() }
        // Following the theme, or picking its colour again: keep following it rather than pinning its current colour.
        if (c == null || hex(c) == hex(theme)) { looks.remove(listOf("c_$key")); changed(); return@colorSheet }
        val low = m3.with(mapOf(key to c)).lowContrast()
        if (low == null) save(c)
        else dialog().setTitle(R.string.low_contrast).setMessage(getString(R.string.low_contrast_info, String.format("%.1f", low.first), String.format("%.1f", low.second)))
            .setPositiveButton(R.string.save_anyway) { _, _ -> save(c) }
            .setNegativeButton(R.string.cancel, null).show()
    }
}

/** What a part colour setting draws in while it follows the theme ("system"), as home draws it. */
private fun SettingsActivity.partTheme(key: String) = when (key) {
    "folderColor" -> m3.surfaceHigh
    else -> m3.surface // dock and search bar
}

/**
 * Picks a part's colour (folders, dock, search bar, labels, badges) in the same sheet as the theme's colours, over a sample of
 * that part. "From the theme" (or "Default" for parts the theme doesn't colour) saves the setting's default back.
 */
fun SettingsActivity.pickColor(c: Choice, save: (String) -> Unit) {
    val themed = "system" in c.values
    val auto = if (themed) partTheme(c.key) else Color.parseColor(c.default)
    val (sample, paint) = partSample(c.key)
    colorSheet(c.title, if (looks[c] == "system") auto else looks.color(c.key), looks[c] == c.default, auto,
        if (themed) R.string.color_from_theme else R.string.color_default, sample, paint) { picked ->
        save(if (picked == null || !themed && hex(picked) == hex(auto)) c.default else hex(picked))
    }
}

/** A small picture of the part [key] colours, on a stand-in wallpaper, and how to paint it in a colour. */
private fun SettingsActivity.partSample(key: String): Pair<View, (Int) -> Unit> {
    val pad = (12 * dp).toInt()
    val icons = List(4) { m3.raised }
    // App keys, as in the scheme sample.
    fun icon(color: Int, size: Int = 36) = View(this).apply {
        background = rounded(color, size * dp * 0.3f)
        layoutParams = LinearLayout.LayoutParams((size * dp).toInt(), (size * dp).toInt()).apply { setMargins(pad / 3, pad / 3, pad / 3, pad / 3) }
    }
    fun row(views: List<View>) = LinearLayout(this).apply { gravity = Gravity.CENTER; views.forEach(::addView) }
    fun ink(c: Int) = if (Color.luminance(c) > 0.5f) 0xFF1B1B1F.toInt() else 0xFFF1F0F4.toInt()
    val wall = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        minimumHeight = (128 * dp).toInt()
        setPadding(pad, pad, pad, pad)
        // ponytail: one flat slate stands in for every wallpaper; dark enough for the default white labels.
        background = rounded(0xFF3A4656.toInt(), 16 * dp)
    }
    val paint: (Int) -> Unit = when (key) {
        "folderColor" -> {
            val name = text(getString(R.string.app_name), 13f).apply { gravity = Gravity.CENTER }
            val window = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                addView(row(icons.map { icon(it, 30) })); addView(name)
            }
            wall.addView(window, LinearLayout.LayoutParams(-2, -2));
            { c -> window.background = rounded(c or 0xFF000000.toInt(), 20 * dp); name.setTextColor(ink(c)) }
        }
        "dockColor" -> {
            val bar = row(icons.map { icon(it) }).apply { setPadding(pad, pad / 2, pad, pad / 2) }
            wall.addView(bar, LinearLayout.LayoutParams(-1, -2));
            { c -> bar.background = rounded(c, 24 * dp) }
        }
        "searchColor" -> {
            val pill = text(getString(R.string.color_preview_search), 15f).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(pad * 3 / 2, 0, pad, 0) }
            wall.addView(pill, LinearLayout.LayoutParams(-1, (48 * dp).toInt()));
            { c -> pill.background = rounded(c, 24 * dp); pill.setTextColor(ink(c)) }
        }
        "badgeColor" -> {
            val badges = icons.map { View(this) }
            wall.addView(row(icons.zip(badges).map { (ic, b) ->
                android.widget.FrameLayout(this).apply {
                    addView(icon(ic, 44).apply { layoutParams = android.widget.FrameLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt(), Gravity.BOTTOM or Gravity.START).apply { setMargins(0, pad / 2, pad / 2, 0) } })
                    addView(b, android.widget.FrameLayout.LayoutParams((14 * dp).toInt(), (14 * dp).toInt(), Gravity.TOP or Gravity.END))
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(pad / 2, 0, pad / 2, 0) }
                }
            }));
            { c -> badges.forEach { it.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) } } }
        }
        else -> { // labelColor: icons with their names under them
            val names = icons.map { text(getString(R.string.app_name), 12f).apply { gravity = Gravity.CENTER } }
            wall.addView(row(icons.zip(names).map { (ic, n) ->
                LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; addView(icon(ic, 44)); addView(n) }
            }));
            { c -> names.forEach { it.setTextColor(c) } }
        }
    }
    return wall to paint
}

/**
 * The one colour sheet: [sample] painted live in the colour being picked, a chip that follows the theme (or the default),
 * swatches, a hex field beside the chip, and hue/saturation/brightness sliders folded under "Exact color". Done calls [save] with the
 * colour, or null when following the theme.
 */
private fun VulcanActivity.colorSheet(title: Int, start: Int, following: Boolean, auto: Int, autoName: Int,
                                        sample: View, paint: (Int) -> Unit, save: (Int?) -> Unit) {
    val pad = (20 * dp).toInt()
    val hsv = FloatArray(3).also { Color.colorToHSV(start, it) }
    var color = start
    var follow = following
    var updating = false
    var saveButton: android.widget.Button? = null

    val chip = text(getString(autoName), 14f).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(pad * 3 / 5, 0, pad * 3 / 5, 0)
    }
    val rings = ArrayList<Pair<View, Int>>()
    val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    // The hex, beside the chip: type or paste a colour.
    val field = EditText(this).apply {
        setSingleLine(); setTextColor(m3.onSurface); setHintTextColor(m3.onSurfaceVariant); textSize = 14f; hint = "#RRGGBB"
        typeface = android.graphics.Typeface.MONOSPACE
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        filters = arrayOf(android.text.InputFilter.LengthFilter(7))
        contentDescription = getString(R.string.exact_color)
        // A slot pressed into the sheet, led by a dot in the colour it holds.
        background = rounded(m3.well, 16 * dp)
        setPadding((6 * dp).toInt(), 0, pad * 3 / 5, 0)
        compoundDrawablePadding = (8 * dp).toInt()
    }
    val fieldDot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp.toInt(), m3.outlineVariant); setSize((20 * dp).toInt(), (20 * dp).toInt()) }
    field.setCompoundDrawablesRelative(fieldDot.apply { setBounds(0, 0, (20 * dp).toInt(), (20 * dp).toInt()) }, null, null, null)
    class Slider(val name: Int, val limit: Int, val unit: String, val get: () -> Int, val set: (Int) -> Unit) {
        val label = text("", 14f)
        val bar = android.widget.SeekBar(this@colorSheet).apply { max = limit; contentDescription = getString(name) }
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
        fieldDot.setColor(color or 0xFF000000.toInt())
        // The picked swatch: a ring in ink a gap away from it, and a check in the colour's own readable ink.
        for ((v, sc) in rings) {
            val on = !follow && hex(sc) == hex(color)
            ((v.background as android.graphics.drawable.LayerDrawable).getDrawable(0) as GradientDrawable).setStroke((2 * dp).toInt(), if (on) m3.onSurface else 0)
            (v as android.widget.ImageView).setImageDrawable(if (on) symbol("check", onColor(sc), 18f) else null)
            v.isSelected = on // TalkBack hears the ring and check as "selected"
        }
        chip.text = (if (follow) "✓  " else "") + getString(autoName)
        chip.isSelected = follow; chip.contentDescription = getString(autoName)
        chip.background = rounded(if (follow) m3.raised else 0, 16 * dp, if (follow) 0 else m3.outlineVariant)
        saveButton?.isEnabled = true
        updating = false
        paint(color)
    }
    chip.setOnClickListener { follow = true; update(auto, false) }

    for (row in swatches()) grid.addView(LinearLayout(this).apply {
        // Square cells: a circle as wide as its share of the row.
        for (sc in row) addView(object : android.widget.ImageView(context) { override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w) }.apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER
            // Outer ring (shown when picked) around the swatch, inset to leave a gap; swatches close to the sheet's own tone keep a hairline.
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                GradientDrawable().apply { shape = GradientDrawable.OVAL },
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(sc); if (contrast(sc, m3.surfaceHigh) < 1.6) setStroke(dp.toInt(), m3.outlineVariant) },
            )).apply { setLayerInset(1, (5 * dp).toInt(), (5 * dp).toInt(), (5 * dp).toInt(), (5 * dp).toInt()) }
            contentDescription = hex(sc)
            setOnClickListener { follow = false; update(sc, false) }
            rings += this to sc
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins((2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt()) })
    })

    // Exact color: folded away, as most picks are a swatch.
    val exact = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        for (s in sliders) {
            addView(s.label.apply { setPadding(0, pad / 2, 0, 0) })
            s.bar.progressDrawable = android.graphics.drawable.LayerDrawable(arrayOf(s.track)).apply {
                setLayerGravity(0, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL); setLayerHeight(0, (8 * dp).toInt())
            }
            s.bar.thumbTintList = android.content.res.ColorStateList.valueOf(m3.onSurface)
            s.bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: android.widget.SeekBar, v: Int, user: Boolean) { if (!updating) { s.set(v); follow = false; update(null, false) } }
                override fun onStartTrackingTouch(b: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(b: android.widget.SeekBar) {}
            })
            fun step(sign: Int, label: Int) = text(if (sign < 0) "−" else "+", 22f).apply {
                gravity = Gravity.CENTER
                background = pressable(null, 20 * dp)
                contentDescription = getString(label, getString(s.name))
                setOnClickListener { s.set((s.get() + sign).coerceIn(0, s.limit)); follow = false; update(null, false) }
            }
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(step(-1, R.string.step_less), LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()))
                addView(s.bar, LinearLayout.LayoutParams(0, (40 * dp).toInt(), 1f))
                addView(step(1, R.string.step_more), LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()))
            })
        }
    }
    val exactHeader = text(getString(R.string.exact_color) + "  ⌄", 15f).apply {
        gravity = Gravity.CENTER_VERTICAL
        minHeight = (48 * dp).toInt()
        background = pressable(null, 8 * dp)
        setOnClickListener {
            val open = exact.visibility != View.VISIBLE
            exact.visibility = if (open) View.VISIBLE else View.GONE
            text = getString(R.string.exact_color) + if (open) "  ⌃" else "  ⌄"
        }
    }
    val controls = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; addView(chip, LinearLayout.LayoutParams(-2, (32 * dp).toInt()))
            addView(field, LinearLayout.LayoutParams(0, (32 * dp).toInt(), 1f).apply { marginStart = pad / 2 })
        },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = pad / 2 })
        addView(grid)
        addView(exactHeader, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad / 2 })
        addView(exact)
    }
    field.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable) {
            if (updating) return
            val c = Regex("#?[0-9a-fA-F]{6}").matchEntire(s.trim())?.let { Color.parseColor("#" + it.value.removePrefix("#")) }
            if (c == null) { saveButton?.isEnabled = false; return } // save only what the field shows
            if (hex(c) != hex(color)) { follow = false; update(c, true) } else saveButton?.isEnabled = true
        }
    })
    update(color, false)

    // The controls scroll beside a fixed sample: under it in portrait, next to it in landscape, where there's no height
    // for both. Capped so the sample and buttons stay on screen.
    val wide = resources.displayMetrics.run { widthPixels > heightPixels }
    val scroll = object : android.widget.ScrollView(this) {
        override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(
            (resources.displayMetrics.heightPixels * if (wide) 0.5f else 0.45f).toInt(), MeasureSpec.AT_MOST))
    }.apply { addView(controls) }
    val box = LinearLayout(this).apply {
        orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        setPadding(pad, pad / 2, pad, 0)
        if (wide) {
            addView(sample, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = pad })
            addView(scroll, LinearLayout.LayoutParams(0, -2, 1f))
        } else {
            addView(sample, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = pad / 2 })
            addView(scroll)
        }
    }
    sheet().setTitle(title).setView(box)
        .setPositiveButton(R.string.done) { _, _ -> save(if (follow) null else color) }
        .setNegativeButton(R.string.cancel, null).show()
        .also { d ->
            saveButton = d.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            // Legend ink, not the accent: the accent is what's being picked here (DESIGN.md, the Ink Not Accent Rule).
            listOf(android.app.AlertDialog.BUTTON_POSITIVE, android.app.AlertDialog.BUTTON_NEGATIVE).forEach {
                d.getButton(it)?.setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                    intArrayOf(blend(m3.onSurface, 0, 0.62f), m3.onSurface)))
            }
        }
}

/** Picks a drawer tab's colour ([current] null: from the theme), over a sample of the tab bar with the tab selected. */
fun VulcanActivity.pickTabColor(name: String, current: String?, done: (String?) -> Unit) {
    val pad = (12 * dp).toInt()
    fun pill(label: String) = text(label, 14f).apply { gravity = Gravity.CENTER; setPadding(pad * 3 / 2, 0, pad * 3 / 2, 0) }
    val all = pill(getString(R.string.tab_all)).apply { setTextColor(m3.onSurfaceVariant) }
    val tab = pill(name)
    val bar = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(pad, pad, pad, pad)
        background = rounded(m3.surface, 16 * dp, m3.outlineVariant)
        addView(all, LinearLayout.LayoutParams(-2, (40 * dp).toInt()).apply { marginEnd = pad })
        addView(tab, LinearLayout.LayoutParams(-2, (40 * dp).toInt()))
    }
    val auto = tabFill()
    colorSheet(R.string.tab_color, current?.let(Color::parseColor) ?: auto, current == null, auto, R.string.color_from_theme, bar, { c ->
        tab.background = rounded(c, 20 * dp); tab.setTextColor(onColor(c))
    }) { c -> done(c?.let(::hex)) }
}

/**
 * Theme color as four cards, each a small picture of that look: Appliance, the wallpaper's colours, AMOLED Black, and
 * your own colour, which opens the colour sheet to pick the colour the theme is built from. Saves "appliance", "system",
 * "amoled" or a hex, as before.
 */
fun SettingsActivity.pickTheme(c: Choice, save: (String) -> Unit) {
    val pad = (12 * dp).toInt()
    val now = looks[c]
    val mine = now.takeIf { it.startsWith("#") }
    val cards = listOf("appliance" to R.string.theme_appliance, "system" to R.string.theme_wallpaper,
        "amoled" to R.string.theme_amoled, (mine ?: "#1E88E5") to R.string.theme_yours)
    lateinit var d: android.app.AlertDialog
    fun card(value: String, title: Int): View {
        val p = palette(this, darkPanels, value, looks.colors())
        val chosen = value == now || title == R.string.theme_yours && mine != null
        val picture = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = rounded(p.surface, 14 * dp, m3.outlineVariant)
            addView(View(context).apply { background = rounded(p.raised, 6 * dp) }, LinearLayout.LayoutParams(-1, (18 * dp).toInt()))
            addView(View(context).apply { background = rounded(p.onSurfaceVariant, 3 * dp) }, LinearLayout.LayoutParams((56 * dp).toInt(), (6 * dp).toInt()).apply { topMargin = pad * 2 / 3 })
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(View(context).apply { background = rounded(p.well, 9 * dp) }, LinearLayout.LayoutParams(0, (18 * dp).toInt(), 1f).apply { marginEnd = pad / 2 })
                addView(View(context).apply { background = rounded(p.signal, 9 * dp) }, LinearLayout.LayoutParams((28 * dp).toInt(), (18 * dp).toInt()))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad * 2 / 3 })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
            background = if (chosen) rounded(m3.secondaryContainer, 18 * dp) else pressable(null, 18 * dp)
            addView(picture)
            addView(text((if (chosen) "✓  " else "") + getString(title), 14f).apply { setPadding(pad / 3, pad / 2, 0, pad / 3) })
            contentDescription = getString(title)
            isSelected = chosen
            setOnClickListener {
                d.dismiss()
                if (title != R.string.theme_yours) return@setOnClickListener save(value)
                // Your color: the sheet's chip goes back to the wallpaper's colours.
                val (preview, paintScheme) = schemePreview()
                val wallpaper = palette(this@pickTheme, darkPanels, "system").signal
                colorSheet(R.string.theme_yours, mine?.let(Color::parseColor) ?: wallpaper, false, wallpaper, R.string.theme_wallpaper, preview,
                    { paintScheme(palette(this@pickTheme, darkPanels, hex(it), looks.colors())) }) { picked -> save(picked?.let(::hex) ?: "system") }
            }
        }
    }
    val grid = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, 0, pad, 0)
        cards.chunked(2).forEach { two ->
            addView(LinearLayout(context).apply { two.forEach { (v, t) -> addView(card(v, t), LinearLayout.LayoutParams(0, -2, 1f)) } })
        }
    }
    d = sheet().setTitle(c.title).setView(grid).setNegativeButton(R.string.cancel, null).show()
}
