package dev.vulcanlauncher

import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.pow
import kotlin.math.roundToInt

// First run: a short guided flow on the brand's charcoal. The sun rises, then one idea per step: hello, make Vulcan
// the home app (only when it isn't), and bring a layout over or start fresh. Shown once, on a fresh install.

/** In Store.data: true from a fresh install until the user picks how to begin. */
const val WELCOME = "welcome"
/** In Store.data: the step on screen, so a restart (becoming the home app relaunches Vulcan) resumes there. */
private const val WELCOME_STEP = "welcomeStep"

private const val CHARCOAL = 0xFF2A2A28.toInt()
private const val WARM_WHITE = 0xFFE9E7E2.toInt()
private const val QUIET = 0xFFA9A69F.toInt()
private const val EDGE = 0xFF4A4945.toInt()
/** Material's emphasized decelerate: things arrive fast and settle slowly. */
private val ARRIVE = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

/** Before the first build: a fresh install gets the welcome. Installs from before it existed never do. */
fun MainActivity.markWelcome() {
    if (store.isNew && !store.data.contains(WELCOME)) store.data.edit().putBoolean(WELCOME, true).apply()
}

private val MainActivity.welcome get() = root.findViewWithTag<WelcomeView>(WELCOME)
val MainActivity.welcoming get() = welcome != null

fun MainActivity.isDefaultHome() = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
    PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == packageName

/** In Store.data: the welcome is only the import's review and widget steps (an import started from Settings). */
private const val WELCOME_IMPORT = "welcomeImport"

/** Over home, at the end of build(): home is already laid out under it, so finishing just lifts this away. */
fun MainActivity.showWelcome() {
    if (!store.data.getBoolean(WELCOME, false)) return
    addWelcome(WelcomeView(this))
}

/** An import from Settings: the same review and widget steps as the first run, over home. */
fun MainActivity.showImport(picked: Pair<ImportedLayout, Map<String, String>>) {
    root.findViewWithTag<View>(WELCOME)?.let(root::removeView)
    addWelcome(WelcomeView(this, picked))
}

private fun MainActivity.addWelcome(v: WelcomeView) {
    root.addView(v, FrameLayout.LayoutParams(-1, -1))
    v.requestApplyInsets() // added over a home already on screen (an import from Settings): insets aren't sent again on their own
    @Suppress("DEPRECATION") // light system icons over the charcoal
    window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and
        (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
}

/**
 * A Nova backup, ready for the review: what this phone can't open (Nova's own settings screen, an app not installed
 * here) would vanish from home anyway, so it's left out of the preview and the counts and said not to come over.
 * Null (and said) when the file isn't one.
 */
fun MainActivity.readForReview(uri: android.net.Uri): Pair<ImportedLayout, Map<String, String>>? =
    runCatching { readLauncherBackup(this, uri) }
        .onFailure { toast(getString(R.string.import_failed, it.message)) }
        .getOrNull()?.let { (layout, settings) ->
            val kept = layout.items.filter { it.folder || it.isWidgetSlot || resolve(it) != null }
            ImportedLayout(kept, layout.subgrid, layout.skipped + layout.items.size - kept.size) to settings
        }

/** Back from Android's home prompt: move on once Vulcan is the home app. */
fun MainActivity.refreshWelcome() = welcome?.resumed()

fun MainActivity.welcomeBack() = welcome?.back()

/** [import]: a Nova backup picked in Settings; the welcome is then just its review (and widget) steps. */
class WelcomeView(private val a: MainActivity, import: Pair<ImportedLayout, Map<String, String>>? = null) : FrameLayout(a) {
    private val dp = a.dp
    private fun px(v: Float) = (v * dp).roundToInt()
    /** Only the import's steps, without hello and setup: started from Settings, or its rebuild for the widget step. */
    private val importOnly = import != null || a.store.data.getBoolean(WELCOME_IMPORT, false)
    private val steps = (if (importOnly) mutableListOf() else mutableListOf("hello").apply {
        if (!a.isDefaultHome()) add("home")
        add("setup")
    }).apply {
        if (import != null) add("review")
        // Just imported, with widgets to bring back: Android's permission prompt comes from this step, not over home.
        if (import == null && a.store.data.getBoolean(RESTORING_WIDGETS, false) && a.items.any { it.isWidgetSlot }) add("widgets")
        if (isEmpty()) add("widgets") // ponytail: an import-only welcome whose widgets went meanwhile; its Continue just finishes
    }
    /** Continue was tapped on "widgets": once the restore stops, the welcome is done. */
    private var restoring = false
    /** The Nova backup picked on "setup", shown on "review" before it replaces anything. */
    private var picked: Pair<ImportedLayout, Map<String, String>>? = import
    // Becoming the home app relaunches Vulcan, and that step is gone from the list then; a review lost its file. Both
    // carry on from "setup".
    private var at = if (importOnly) 0 else a.store.data.getString(WELCOME_STEP, "")!!.let { saved ->
        steps.indexOf(saved).takeIf { it >= 0 } ?: if (saved.isEmpty()) 0 else steps.indexOf("setup")
    }
    private val sunSize = px(300f)
    private val sun = SunriseView(a, risen = steps[at] != "hello")
    private val sunArea = View(a)
    private val stage = FrameLayout(a)
    private var topInset = 0
    private var area = 0
    private var areaAnim: ValueAnimator? = null

    init {
        tag = WELCOME
        setBackgroundColor(CHARCOAL)
        isClickable = true // nothing reaches home under it
        addView(sun, LayoutParams(sunSize, sunSize, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(sunArea, LinearLayout.LayoutParams(-1, 0))
            addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        }, LayoutParams(minOf(resources.displayMetrics.widthPixels, px(460f)), -1, Gravity.CENTER_HORIZONTAL))
        setOnApplyWindowInsetsListener { _, ins ->
            topInset = ins.systemWindowInsetTop
            stage.setPadding(px(28f), 0, px(28f), ins.systemWindowInsetBottom + px(20f))
            post { place(animate = false) }
            ins
        }
        val first = build(steps[at])
        stage.addView(first)
        // The hello waits for the sunrise; a later step (a restart midway, the import's rebuild) eases in.
        if (ValueAnimator.areAnimatorsEnabled()) {
            hide(first)
            if (steps[at] == "hello") sun.onRise = { reveal(first, 650) } else post { reveal(first, 60) }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        place(animate = false) // the sun's place, before its first frame
        post { place(animate = false) } // and the room under it: a size set during this layout pass would be dropped
    }

    /** The sun's room at the top: large for the hello, then small above each step's title. */
    private fun place(animate: Boolean) {
        if (height == 0) return
        val hello = steps[at] == "hello"
        val review = steps[at] == "review" || steps[at] == "widgets"
        val target = topInset + (height * if (hello) 0.47f else if (review) 0.1f else 0.25f).roundToInt()
        val scale = if (hello) 1f else if (review) 0.26f else 0.4f
        areaAnim?.cancel()
        fun set(v: Int) {
            area = v
            sunArea.layoutParams = sunArea.layoutParams.apply { height = v }
            sun.translationY = v * (if (hello) 0.6f else 0.62f) - sunSize / 2f + (if (hello) 0f else px(8f).toFloat())
        }
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            set(target); sun.scaleX = scale; sun.scaleY = scale; return
        }
        val from = area
        val fromScale = sun.scaleX
        areaAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 640
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                val f = it.animatedValue as Float
                set((from + (target - from) * f).roundToInt())
                val s = fromScale + (scale - fromScale) * f
                sun.scaleX = s; sun.scaleY = s
            }
            start()
        }
    }

    private fun build(step: String): LinearLayout = when (step) {
        "review" -> buildReview()
        "widgets" -> buildWidgets()
        else -> buildStep(step)
    }

    /**
     * Before Android's own widget prompt (which no app can restyle): the new home in miniature with its widget spots,
     * what Android will ask, and the one box worth ticking so it asks once.
     */
    private fun buildWidgets() = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val slots = a.items.count { it.isWidgetSlot }
        addView(title(a.resources.getQuantityString(R.plurals.widgets_title, slots, slots), 30f))
        addView(LayoutPreview(a, ImportedLayout(a.items.filter { it.page == 0 || it.page == -1 }, a.sub == 2, 0),
            a.looks.int("cols"), a.looks.int("rows"), a.looks.int("dockCols")), LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(20f) })
        addView(body(a.getString(R.string.widgets_body)).apply { textSize = 15f }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(18f) })
        addView(alwaysAllow(), LinearLayout.LayoutParams(-2, px(40f)).apply { topMargin = px(14f) })
        addView(View(a), LinearLayout.LayoutParams(1, 0, 1f))
        addView(primary(R.string.widgets_continue) {
            if (!a.store.data.getBoolean(RESTORING_WIDGETS, false)) finish() else { restoring = true; a.restoreNextWidget() }
        }, keyParams())
        addView(quiet(R.string.widgets_skip) { a.store.data.edit().remove(RESTORING_WIDGETS).apply(); finish() }, keyParams(gap = 8f, h = 48f))
    }

    /** A picture of the box to tick in Android's prompt: a checked square and its words, in a quiet outlined pill. */
    private fun alwaysAllow() = LinearLayout(a).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(14f), 0, px(18f), 0)
        background = GradientDrawable().apply { cornerRadius = 20 * dp; setStroke(dp.roundToInt(), EDGE) }
        addView(android.widget.ImageView(a).apply {
            setImageDrawable(Symbol("check", CHARCOAL, px(16f)))
            scaleType = android.widget.ImageView.ScaleType.CENTER
            background = GradientDrawable().apply { cornerRadius = 4 * dp; setColor(WARM_WHITE) }
        }, LinearLayout.LayoutParams(px(20f), px(20f)).apply { marginEnd = px(10f) })
        addView(TextView(a).apply {
            text = a.getString(R.string.widgets_always)
            textSize = 14f
            setTextColor(WARM_WHITE)
        })
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private fun buildStep(step: String) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val hello = step == "hello"
        addView(title(if (hello) a.getString(R.string.app_name) else a.getString(
            if (step == "home") R.string.welcome_home_title else R.string.welcome_setup_title), if (hello) 56f else 32f))
        addView(body(a.getString(when (step) {
            "hello" -> R.string.welcome_tagline
            "home" -> R.string.welcome_home_body
            else -> R.string.welcome_setup_body
        })), LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(if (hello) 12f else 16f) })
        addView(View(a), LinearLayout.LayoutParams(1, 0, 1f))
        when (step) {
            "hello" -> addView(primary(R.string.welcome_begin) { go(at + 1) }, keyParams())
            "home" -> {
                addView(primary(R.string.welcome_home_set) { askHomeRole() }, keyParams())
                addView(quiet(R.string.welcome_not_now) { go(at + 1) }, keyParams(gap = 8f, h = 48f))
            }
            else -> {
                addView(primary(R.string.welcome_nova) { pickNova() }, keyParams())
                addView(secondary(R.string.welcome_restore) { pick { a.restoreBackup(it, ::begin) } }, keyParams(gap = 12f))
                addView(quiet(R.string.welcome_fresh) { finish() }, keyParams(gap = 8f, h = 48f))
            }
        }
    }

    /** The picked layout in miniature, what it holds, and what comes back differently: then Import, or another file. */
    private fun buildReview() = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val (layout, settings) = picked ?: return@apply
        addView(title(a.getString(R.string.review_title), 30f))
        addView(LayoutPreview(a, layout, settings["cols"]?.toIntOrNull() ?: a.looks.int("cols"), settings["rows"]?.toIntOrNull() ?: a.looks.int("rows"),
            settings["dockCols"]?.toIntOrNull() ?: a.looks.int("dockCols")), LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(20f) })
        val items = layout.items
        val pages = (items.filter { !isDock(it.page) }.maxOfOrNull { it.page } ?: 0) + 1
        val counts = listOfNotNull(
            items.count { !it.folder && !it.isWidgetSlot }.takeIf { it > 0 }?.let { a.resources.getQuantityString(R.plurals.review_apps, it, it) },
            items.count { it.folder }.takeIf { it > 0 }?.let { a.resources.getQuantityString(R.plurals.review_folders, it, it) },
            layout.widgets.takeIf { it > 0 }?.let { a.resources.getQuantityString(R.plurals.review_widgets, it, it) },
            pages.takeIf { it > 1 }?.let { a.resources.getQuantityString(R.plurals.review_pages, it, it) },
        )
        addView(TextView(a).apply {
            text = counts.joinToString("  ·  ").uppercase()
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(QUIET)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(16f) })
        a.importNote(layout, lead = false, separator = "\n").takeIf { it.isNotEmpty() }?.let { note ->
            addView(body(note).apply { textSize = 14f }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(8f) })
        }
        addView(View(a), LinearLayout.LayoutParams(1, 0, 1f))
        addView(primary(R.string.import_) {
            a.applyImport(layout, settings)
            if (layout.widgets == 0) begin()
            else { // home rebuilt, then the widgets step
                a.store.data.edit().putBoolean(WELCOME, true).putString(WELCOME_STEP, "widgets").putBoolean(WELCOME_IMPORT, importOnly).commit()
                a.recreate()
            }
        }, keyParams())
        addView(quiet(R.string.review_another) { pickNova() }, keyParams(gap = 8f, h = 48f))
    }

    /** A Nova backup from the file picker: read now, so a file that isn't one is said at once, then the review. */
    private fun pickNova() = pick { uri ->
        a.readForReview(uri)?.let {
            picked = it
            if ("review" !in steps) steps += "review"
            go(steps.indexOf("review"), again = true)
        }
    }

    private fun keyParams(gap: Float = 0f, h: Float = 56f) = LinearLayout.LayoutParams(-1, px(h)).apply { topMargin = px(gap) }

    private fun title(s: String, sp: Float) = TextView(a).apply {
        text = s
        textSize = sp
        setTextColor(WARM_WHITE)
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        letterSpacing = -0.02f
        gravity = Gravity.CENTER
        isFocusable = true
    }

    private fun body(s: String) = TextView(a).apply {
        text = s
        textSize = 16f
        setTextColor(QUIET)
        gravity = Gravity.CENTER
        maxWidth = px(320f)
        setLineSpacing(0f, 1.2f)
    }

    private fun key(label: Int, fill: Int, ink: Int, stroke: Int, onClick: () -> Unit) = TextView(a).apply {
        text = a.getString(label)
        textSize = 16f
        setTextColor(ink)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        val shape = GradientDrawable().apply { cornerRadius = 28 * dp; setColor(fill); if (stroke != 0) setStroke(dp.roundToInt(), stroke) }
        background = RippleDrawable(ColorStateList.valueOf(ink and 0xFFFFFF or 0x24000000), shape,
            GradientDrawable().apply { cornerRadius = 28 * dp; setColor(-1) })
        setOnClickListener { it.confirm(); onClick() }
    }
    private fun primary(label: Int, onClick: () -> Unit) = key(label, WARM_WHITE, CHARCOAL, 0, onClick)
    private fun secondary(label: Int, onClick: () -> Unit) = key(label, 0, WARM_WHITE, EDGE, onClick)
    private fun quiet(label: Int, onClick: () -> Unit) = key(label, 0, QUIET, 0, onClick).apply { textSize = 15f }

    private fun hide(v: ViewGroup) = (0 until v.childCount).forEach { v.getChildAt(it).apply { alpha = 0f; translationY = 28 * dp } }

    /** Each line arrives in turn: title, words, then the keys. */
    private fun reveal(v: ViewGroup, delay: Long) {
        hide(v)
        (0 until v.childCount).forEach { i ->
            v.getChildAt(i).animate().alpha(1f).translationY(0f).setStartDelay(delay + i * 70L).setDuration(620).setInterpolator(ARRIVE).start()
        }
    }

    /** [again]: rebuild even when [to] is the step on screen (another file picked on the review). */
    private fun go(to: Int, again: Boolean = false) {
        if (to !in steps.indices || to == at && !again) return
        val forward = to > at
        at = to
        a.store.data.edit().putString(WELCOME_STEP, steps[at]).apply()
        if (steps[at] != "review") { steps.remove("review"); picked = null }
        stage.getChildAt(stage.childCount - 1)?.let { old ->
            old.animate().alpha(0f).translationY((if (forward) -20 else 20) * dp).setStartDelay(0).setDuration(200)
                .setInterpolator(DecelerateInterpolator()).withEndAction { stage.removeView(old) }.start()
        }
        val next = build(steps[at])
        stage.addView(next)
        if (ValueAnimator.areAnimatorsEnabled()) reveal(next, 160)
        place(animate = true)
    }

    fun back() = if (at > 0) go(at - 1) else finish()

    fun resumed() {
        if (steps[at] == "home" && a.isDefaultHome()) postDelayed({ go(at + 1) }, 250)
        // Every widget is back (or the user stopped): lift away onto the finished home.
        if (steps[at] == "widgets" && restoring && !a.store.data.getBoolean(RESTORING_WIDGETS, false)) { restoring = false; postDelayed({ finish() }, 300) }
    }

    /** Android's own "use as home app" prompt (10+), or its default-apps page. */
    private fun askHomeRole() {
        if (Build.VERSION.SDK_INT >= 29) {
            val roles = a.getSystemService(android.app.role.RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) &&
                runCatching { a.startActivityForResult(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), MainActivity.REQ_HOME) }.isSuccess) return
        }
        runCatching { a.startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS)) }
    }

    private fun pick(onPicked: (android.net.Uri) -> Unit) {
        a.onDocument = onPicked
        a.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), MainActivity.REQ_DOC)
    }

    private fun done() = a.store.data.edit().putBoolean(WELCOME, false).remove(WELCOME_STEP).remove(WELCOME_IMPORT).commit()

    /** An import or restore went through: home starts again from what was stored, without the welcome. */
    private fun begin() { done(); a.recreate() }

    /** Start fresh: the words go, then the whole room opens onto the home already laid out under it. */
    fun finish() {
        done()
        tag = null
        isClickable = false
        stage.animate().alpha(0f).setDuration(180).start()
        sun.animate().alpha(0f).scaleX(sun.scaleX * 0.8f).scaleY(sun.scaleY * 0.8f).setDuration(260).start()
        animate().alpha(0f).scaleX(1.08f).scaleY(1.08f).setStartDelay(140).setDuration(460).setInterpolator(ARRIVE)
            .withEndAction { (parent as? ViewGroup)?.removeView(this) }.start()
        @Suppress("DEPRECATION") // home's own status bar icons again (build() sets them from the setting)
        a.window.decorView.systemUiVisibility = a.window.decorView.systemUiVisibility or
            (if (a.looks.on("darkStatusIcons")) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0)
    }
}

/**
 * The sunset logo drawn straight onto the charcoal, in its fixed brand colours, with the warm light it gives off.
 * On first show the horizon draws out from the middle and the sun rises from behind it, lighting the room.
 * Geometry is the launcher icon's (ic_launcher_foreground.xml).
 */
class SunriseView(private val a: VulcanActivity, risen: Boolean) : View(a) {
    private var p = if (risen || !ValueAnimator.areAnimatorsEnabled()) 1f else 0f
    private var played = p == 1f
    /** Runs as the sun starts to rise (the words follow it). */
    var onRise: (() -> Unit)? = null
    private val sun = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SIGNAL }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val horizon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE9E7E2.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    // Once the window is in front: on a cold start Android's opening transition would otherwise hide the sunrise.
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (hasWindowFocus()) play() }
    override fun onWindowFocusChanged(focus: Boolean) { super.onWindowFocusChanged(focus); if (focus) play() }

    private fun play() {
        if (played) return
        played = true
        onRise?.invoke()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2200
            startDelay = 150
            interpolator = null // linear: each part eases itself below
            addUpdateListener { p = it.animatedValue as Float; invalidate() }
        }.start()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        // The icon's 108-unit canvas, its 72-unit visible circle across 54% of the view; the rest is room for the light.
        val s = width * 0.54f / 72f
        fun y(v: Float) = cy + (v - 54f) * s
        val base = y(58.44f)
        val sr = 23.92f * s
        val t = ((p - 0.15f) / 0.85f).coerceIn(0f, 1f)
        val rise = 1 - (1 - t).pow(4) // a slow settle, not a pop

        // The light comes up with the sun.
        if (rise > 0f) {
            val r = width / 2f
            glow.shader = RadialGradient(cx, base, r, intArrayOf(SIGNAL.withAlpha((26 * rise).roundToInt()), SIGNAL.withAlpha((8 * rise).roundToInt()), 0),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, base, r, glow)
        }
        val drawn = 1 - (1 - (p / 0.4f).coerceIn(0f, 1f)).pow(3)
        if (drawn > 0.01f) {
            horizon.strokeWidth = 5.06f * s
            val half = 22.08f * s * drawn
            c.drawLine(cx - half, y(66.72f), cx + half, y(66.72f), horizon)
        }
        val sy = base + sr * 1.05f * (1 - rise)
        c.save()
        c.clipRect(0f, 0f, width.toFloat(), base)
        c.drawArc(cx - sr, sy - sr, cx + sr, sy + sr, 180f, 180f, true, sun)
        c.restore()
    }
}

/**
 * An imported layout in miniature: its first page and dock in their cells, with the apps' own icons, folder previews and
 * widget spots drawn dashed. The icons land one after another when it first shows.
 */
class LayoutPreview(private val a: MainActivity, layout: ImportedLayout, private val cols: Int, private val rows: Int, private val dockCols: Int) : View(a) {
    private val dp = a.dp
    private val f = if (layout.subgrid) 2 else 1
    private class Tile(val item: Item, val icon: android.graphics.drawable.Drawable?)
    private val tiles = layout.items.filter { it.page == 0 || it.page == -1 }
        .sortedWith(compareBy({ if (isDock(it.page)) 1 else 0 }, { it.y }, { it.x }))
        .map { Tile(it, iconOf(it)?.let { d -> d.constantState?.newDrawable(resources)?.mutate() ?: d }) }
    private var p = if (ValueAnimator.areAnimatorsEnabled()) 0f else 1f
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = EDGE; strokeWidth = dp }
    private val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x0FFFFFFF }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFFFFFF; strokeWidth = dp }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x8CFFFFFF.toInt(); strokeWidth = dp
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(4 * dp, 3 * dp), 0f)
    }
    private val pad = 12 * dp
    private val gap = 14 * dp

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    private fun iconOf(item: Item) = if (item.isWidgetSlot) a.apps.values.firstOrNull { it.pkg == item.pkg }?.let(a::icon)
        else a.resolve(item)?.second

    private val cellW get() = (width - 2 * pad) / cols
    private val cellH get() = cellW * 1.22f

    override fun onMeasure(w: Int, h: Int) {
        val width = (196 * dp).toInt()
        val cw = (width - 2 * pad) / cols
        setMeasuredDimension(width, (2 * pad + cw * 1.22f * (rows + 1) + gap).toInt())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (p == 1f) return
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500L + tiles.size * 70L
            startDelay = 380
            interpolator = null
            addUpdateListener { p = it.animatedValue as Float; invalidate() }
        }.start()
    }

    override fun onDraw(c: Canvas) {
        val r = 24 * dp
        c.drawRoundRect(dp / 2, dp / 2, width - dp / 2, height - dp / 2, r, r, glass)
        c.drawRoundRect(dp / 2, dp / 2, width - dp / 2, height - dp / 2, r, r, frame)
        val dockTop = pad + cellH * rows + gap
        c.drawLine(pad + cellW, dockTop - gap / 2, width - pad - cellW, dockTop - gap / 2, line)
        val n = tiles.size.coerceAtLeast(1)
        tiles.forEachIndexed { i, t ->
            // Each lands in turn, slightly overshooting its size as it settles.
            val start = i / n.toFloat() * 0.6f
            val k = ((p - start) / 0.4f).coerceIn(0f, 1f)
            if (k <= 0f) return@forEachIndexed
            val q = k - 1
            val grow = 1 + 2.2f * q * q * q + 1.2f * q * q
            val it = t.item
            val left: Float; val top: Float; val w: Float; val h: Float
            if (isDock(it.page)) {
                w = (width - 2 * pad) / dockCols; h = cellH
                left = pad + it.x * w; top = dockTop
            } else {
                left = pad + it.x * cellW / f; top = pad + it.y * cellH / f
                w = it.w * cellW / f; h = it.h * cellH / f
            }
            val cx = left + w / 2
            val cy = top + h / 2
            c.save()
            c.scale(grow, grow, cx, cy)
            val alpha = (255 * k).toInt()
            if (it.isWidgetSlot) {
                dash.alpha = (140 * k).toInt()
                c.drawRoundRect(left + 2 * dp, top + 2 * dp, left + w - 2 * dp, top + h - 2 * dp, 8 * dp, 8 * dp, dash)
            }
            t.icon?.let { d ->
                val s = minOf(w, h) * if (it.isWidgetSlot) 0.34f else 0.66f
                d.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
                d.alpha = alpha
                d.draw(c)
            }
            c.restore()
        }
    }
}
