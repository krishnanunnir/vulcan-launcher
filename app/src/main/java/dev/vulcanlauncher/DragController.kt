package dev.vulcanlauncher

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.pm.ShortcutInfo
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot

/** A page held in the overview, being dragged: onto another page they swap places, onto Remove it goes. */
class PageMove(val index: Int)

/** Drag and drop on home, and the shrunk page it shares with the hold-home overview: the drag in progress, drop targets, spring-loaded pages and page reordering. */
class DragController(private val a: MainActivity) {
    /** Home was built again: the drag, the shrunk page and the extra pages all belonged to the old views. */
    fun reset() {
        wiggles.forEach { it.cancel() }
        wiggles = emptyList()
        dragSource = null; pending = null
        springAnim?.cancel(); spring = 0f; homeState = HomeState.NORMAL; dragPage = null; plusPage = null
    }

    lateinit var dropBar: LinearLayout

    private var lastEdgeFlip = 0L
    var dragSource: View? = null
    private var dragPayload: Any? = null
    private var dragStart: PointF? = null
    var dragMoved = false
        private set

    fun startDrag(v: View, payload: Any) {
        if (a.locked) { a.homeMenus.showPopup(v, payload); return }
        dragSource = v
        dragPayload = payload
        dragStart = null
        dragMoved = false
        dropped = false
        dragFrom = (payload as? Item)?.page?.takeIf { !isDock(it) }
        (dropBar.getChildAt(0) as TextView).text = a.getString(if (payload is Item || payload is FolderChild) R.string.remove else R.string.cancel)
        val system = appOf(payload)?.info?.applicationInfo?.let { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 }
        dropBar.getChildAt(1).visibility = if (system == false) View.VISIBLE else View.GONE
        // As Launcher3: the lifted icon stays where it was under the finger (the default shadow centres on it), and
        // its cell is left empty.
        val at = IntArray(2).also { v.getLocationInWindow(it) }
        val touch = Point((a.lastDown.x - at[0]).toInt().coerceIn(0, v.width), (a.lastDown.y - at[1]).toInt().coerceIn(0, v.height))
        // The system drag waits for the finger to move (onTouch): started at the long-press itself, it held the main
        // thread 25–30 ms (performDrag) and sent a stream of pointer-icon calls, on every press that only wanted the menu.
        pending = { lift(v, payload, touch) }
        // The menu shows the moment the long-press registers, as on the wallpaper; moving the drag closes it (trackDrag).
        // Not for a drag out of a menu row (a shortcut): that menu stays until the drag moves.
        if (a.homeMenus.menu == null) a.root.post { if (dragSource === v && !dragMoved && v.isAttachedToWindow) a.homeMenus.showPopup(v, payload) }
    }

    /** A long-press whose drag starts once the finger moves ([startDrag]); null otherwise. */
    private var pending: (() -> Unit)? = null
    private val slop by lazy { android.view.ViewConfiguration.get(a).scaledTouchSlop } // lazy: made before the activity has a context

    /** The activity's touch stream: a pending drag starts when the finger moves past the slop, and goes on release. */
    fun onTouch(ev: MotionEvent) {
        val p = pending ?: return
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (hypot(ev.x - a.lastDown.x, ev.y - a.lastDown.y) > slop) { pending = null; p() }
            // Released in place: the menu stays, as a drag that never moved. A new DOWN: an accessibility long-press,
            // which has no touch stream of its own.
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_DOWN -> { pending = null; dragSource = null }
        }
    }

    private fun lift(v: View, payload: Any, touch: Point) {
        val started = v.isAttachedToWindow && v.startDragAndDrop(ClipData.newPlainText("", ""), object : View.DragShadowBuilder(v) {
            override fun onProvideShadowMetrics(size: Point, touchPoint: Point) { super.onProvideShadowMetrics(size, touchPoint); touchPoint.set(touch.x, touch.y) }
        }, payload, View.DRAG_FLAG_OPAQUE)
        // No drag means no ENDED to undo what follows; the menu already shows.
        if (!started) { dragSource = null; return }
        if (payload is Item) v.alpha = 0f
        if (a.looks.on("wiggle")) wiggles = a.iconViews.map { iv ->
            android.animation.ObjectAnimator.ofFloat(iv.image, "rotation", -4f, 4f).apply {
                duration = 140
                repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode = android.animation.ValueAnimator.REVERSE
                start()
            }
        }
    }

    private var wiggles = listOf<android.animation.Animator>()

    /**
     * A drag that never moves is a long-press: its menu stays. [keepOverlay]: a folder window reordering its icons, which
     * keeps it and the drawer behind it until the drag leaves the folder.
     */
    fun trackDrag(v: View, e: DragEvent, keepOverlay: Boolean = false) {
        // Only our own drags: one from another window (desktop windowing) has no localState here, and no dragEnded to undo this.
        if (e.action != DragEvent.ACTION_DRAG_LOCATION || e.localState == null || dragSource == null) return
        val loc = IntArray(2).also { v.getLocationOnScreen(it) }
        val x = loc[0] + e.x
        val y = loc[1] + e.y
        // A drag on home keeps the page full size (#106) until it reaches the drop targets' strip at the top.
        if (dragMoved) { if (homeState == HomeState.NORMAL && dragSource != null && y < a.insets.top + 72 * a.dp) setHomeState(HomeState.DRAG); return }
        val s = dragStart ?: PointF(x, y).also { dragStart = it }
        if (hypot(x - s.x, y - s.y) > 16 * a.dp) {
            dragMoved = true
            // As Nova: a drag within a folder keeps home as it is until it leaves the folder (openFolderWindow's leave).
            if (!keepOverlay) liftDrag(shrink = a.drawer.visible || a.overlay != null)
            a.root.tick()
            a.homeMenus.closeMenu()
            if (!keepOverlay) { a.drawer.hide(animate = false); a.closeOverlay() }
        }
    }

    /**
     * Home's drag mode: drop targets and the shrunk page. A drag that never moves stays a long-press: none until it does.
     * [shrink] false (a drag that started on home): both wait until the drag nears the top (trackDrag).
     * A drawer folder's app has no drop targets: it only reorders or copies to home.
     */
    fun liftDrag(shrink: Boolean = true) {
        // Every drag, shrunk or not, strokes its drop outline in the accent: unset it drew black (#106).
        (a.pages + a.dockPages).forEach { it.outlineColor = workspaceAccent() }
        if (shrink) setHomeState(HomeState.DRAG)
        addDragPage()
    }

    /** For views that cover home (drawer, folder window): follow the drag but never accept the drop. */
    val passDrag = View.OnDragListener { v, e -> trackDrag(v, e); e.action != DragEvent.ACTION_DROP }

    fun dropListener(cell: CellLayout) = View.OnDragListener { v, e ->
        if (e.localState == null) return@OnDragListener false // another window's drag: not for home
        (e.localState as? PageMove)?.let { return@OnDragListener pageDrag(cell, e, it) }
        trackDrag(v, e)
        when (e.action) {
            DragEvent.ACTION_DRAG_LOCATION -> {
                edgeFlip(if (isDock(cell.page)) a.dock else a.workspace, e.x, cell.width)
                val merge = a.mergeTarget(cell, e.x, e.y, e.localState)
                a.folderFx.hover(cell, merge)
                val stack = a.widgetFlow.stackTarget(cell, e.x, e.y, e.localState)
                cell.landing = when {
                    stack != null -> Rect(stack.x, stack.y, stack.x + stack.w, stack.y + stack.h)
                    merge != null || !dragMoved -> null
                    else -> landing(cell, e.x, e.y, e.localState)
                }
            }
            DragEvent.ACTION_DRAG_EXITED -> { a.folderFx.hover(cell, null); cell.landing = null }
            DragEvent.ACTION_DROP -> { a.folderFx.clearHover(); return@OnDragListener drop(cell, e.x, e.y, e.localState) }
            // An accepted drop's ENDED goes to the accepting view only, so every cell must clean up too.
            DragEvent.ACTION_DRAG_ENDED -> dragEnded()
        }
        true
    }

    /** Drop target shown at the top while dragging, as in Launcher3: an outline in the label colour, filled while hovered. */
    fun dropTarget(kind: String) = a.text(a.getString(if (kind == "uninstall") R.string.uninstall else R.string.remove), 14f, medium = true).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        val p = (16 * a.dp).toInt()
        setPadding(p, 0, p * 5 / 4, 0)
        compoundDrawablePadding = p / 2
        fun look(hover: Boolean) {
            val line = a.looks.color("labelColor")
            val fg = if (hover) a.m3.onPrimaryContainer else line
            background = if (hover) a.rounded(a.m3.primaryContainer, 24 * a.dp) else a.rounded(0, 24 * a.dp, line, 1.5f * a.dp)
            setTextColor(fg)
            setCompoundDrawablesRelative(a.symbol(if (kind == "uninstall") "delete" else "close", fg, 20f).apply {
                setBounds(0, 0, intrinsicWidth, intrinsicHeight)
            }, null, null, null)
        }
        look(false)
        setOnDragListener { _, e ->
            val p = e.localState
            when (e.action) {
                DragEvent.ACTION_DRAG_ENTERED -> { look(true); performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
                DragEvent.ACTION_DRAG_EXITED -> look(false)
                DragEvent.ACTION_DROP -> when (kind) {
                    "remove" -> when (p) {
                        is PageMove -> a.root.post {
                            a.removePageAt(p.index) { afterRebuild { a.workspace.snapTo(minOf(p.index, a.looks.pages - 1), animate = false); a.homeMenus.homeOverview(animate = false) } }
                        }
                        is Item -> a.removeItem(p)
                        is FolderChild -> if (a.removeFromFolder(p)) a.save()
                    }
                    "uninstall" -> appOf(p)?.let { a.uninstall(it) }
                }
                DragEvent.ACTION_DRAG_ENDED -> { look(false); dragEnded() }
            }
            true
        }
    }

    var spring = 0f
    private var springAnim: ValueAnimator? = null

    /** Space [homeOverview]'s buttons take at the bottom of the screen; 0 for a drag, which has none. */
    var springBottom = 0
    /** The shrunk page's scale, from [springScale]. */
    private var cardScale = SPRING_SCALE

    /**
     * Launcher3's spring-loaded home, for a drag and for the hold-home overview: the pages shrink into outlined cards a
     * gap apart, so the neighbours peek in, and the top strip is left to the drop targets. [on] false grows them back;
     * [animate] false jumps there (a rebuild reopening the overview).
     */
    private fun springLoad(on: Boolean, animate: Boolean = true) {
        val accent = workspaceAccent()
        (a.pages + a.dockPages).forEach { it.outlineColor = accent; if (!on) it.landing = null }
        val box = a.workspace.parent as ViewGroup
        if (on && spring == 0f) {
            var top = 0
            var v: View = a.workspace
            while (v !== a.root) { top += v.top; v = v.parent as View }
            // The cards' top lands just under the drop targets; they scale about the point that puts it there. Their
            // bottom stays 16 dp clear of the overview's buttons; without the dock the page is taller.
            val cardTop = a.insets.top + 76 * a.dp
            cardScale = springScale(a.workspace.height, cardTop, a.root.height - springBottom - 16 * a.dp)
            a.workspace.pivotX = a.workspace.width / 2f
            a.workspace.pivotY = (cardTop - cardScale * top) / (1 - cardScale) - top
            // The neighbours draw outside the shrunk page's bounds.
            a.workspace.clipChildren = false; box.clipChildren = false
        }
        if (on) a.pages.forEach { if (it.background == null) it.background = pageCard().apply { alpha = (spring * 255).toInt() } }
        springAnim?.cancel()
        springAnim = ValueAnimator.ofFloat(spring, if (on) 1f else 0f).apply {
            duration = 300
            interpolator = EASE
            addUpdateListener {
                spring = it.animatedValue as Float
                val s = 1 - (1 - cardScale) * spring
                a.workspace.scaleX = s; a.workspace.scaleY = s
                // 16 dp between the cards on screen.
                a.workspace.gap = 16 * a.dp / cardScale * spring
                a.pages.forEach { p -> p.background?.alpha = (spring * 255).toInt() }
                dropBar.alpha = spring
            }
            if (!on) addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (spring != 0f) return
                    dropBar.visibility = View.GONE
                    this@DragController.a.pages.forEach { it.background = null }
                    this@DragController.a.workspace.clipChildren = true; box.clipChildren = true
                }
            })
            start()
            if (!animate) end()
        }
    }

    /**
     * Launcher3's workspace accent: its light tone over a dark wallpaper (light labels), its dark tone otherwise.
     * The Appliance look keeps to its legends' colour.
     */
    private fun workspaceAccent(): Int {
        val label = a.looks.color("labelColor")
        return if (a.m3.housing) label else a.m3.ramps[0](if (luminance(label) > 0.5) 90 else 10)
    }

    /** A shrunk page's card: a light fill and an outline in the accent. */
    private fun pageCard(): Drawable =
        a.rounded(a.looks.color("labelColor") and 0xFFFFFF or 0x1F000000, 28 * a.dp, workspaceAccent() and 0xFFFFFF or 0x99000000.toInt(), 2 * a.dp)

    fun dragEnded() {
        a.folderFx.clearHover()
        val src = dragSource ?: return // ENDED can fan out to several listeners; first one wins
        dragSource = null
        wiggles.forEach { it.cancel() }
        a.iconViews.forEach { it.image.rotation = 0f }
        wiggles = emptyList()
        // A drag that never shrank home doesn't go through springLoad's cleanup: its last outline stayed (#106).
        (a.pages + a.dockPages).forEach { it.landing = null }
        setHomeState(HomeState.NORMAL)
        src.alpha = 1f
        val moved = dragMoved
        val payload = dragPayload
        // Posted: adding or removing views while ENDED is still being dispatched throws
        // ConcurrentModificationException in ViewGroup.dispatchDragEvent.
        // A drag that never moved already shows its menu (startDrag); it stays open.
        a.root.post { if (!moved && src.isAttachedToWindow) { if (a.homeMenus.menu == null) a.homeMenus.showPopup(src, payload) } else { a.render(); settleDragPages() } }
    }

    /** Home's one state; [setHomeState] changes it. */
    var homeState = HomeState.NORMAL

    /**
     * As Launcher3's state manager: one call puts home in [s], and everything follows from it. The page shrinks into its
     * outline for the hold-home buttons and for a drag; the buttons also take the dock's and page dots' place, and a
     * drag shows its drop targets (not for a drawer folder's app, which only reorders or copies to home).
     */
    fun setHomeState(s: HomeState, animate: Boolean = true) {
        if (s == homeState) return
        homeState = s
        if (s == HomeState.OVERVIEW) addPlusPage() else removePlusPage()
        springLoad(s != HomeState.NORMAL, animate)
        a.workspace.onPageTap = if (s == HomeState.OVERVIEW) ::overviewTap else null
        a.workspace.onPageHold = if (s == HomeState.OVERVIEW) ::overviewHold else null
        val chrome = if (s == HomeState.OVERVIEW) 0f else 1f
        listOf(a.dockBox, a.indicator).forEach { it.animate().alpha(chrome).setDuration(200).start() }
        if (s == HomeState.DRAG && dragPayload !is GroupChild) dropBar.visibility = View.VISIBLE
    }

    /** The overview's last card: tapping its "+" adds a page there (up to the Pages setting's 9). */
    private var plusPage: CellLayout? = null

    private fun addPlusPage() {
        if (plusPage != null || a.locked || a.pages.size >= 9) return
        newPage(a.pages.size).apply {
            setOnLongClickListener(null)
            isLongClickable = false
            foreground = android.graphics.drawable.LayerDrawable(arrayOf(a.symbol("add", a.looks.color("labelColor"), 48f))).apply { setLayerGravity(0, Gravity.CENTER) }
            contentDescription = a.getString(R.string.page_add)
        }.also { plusPage = it; a.pages = a.pages + it; a.workspace.addView(it) }
    }

    private fun removePlusPage() {
        val p = plusPage ?: return
        plusPage = null
        a.pages = a.pages - p
        a.workspace.removeView(p)
        if (a.workspace.current >= a.pages.size) a.workspace.snapTo(a.pages.size - 1)
    }

    /** The overview: a tap opens the page under it, or adds one on "+"; off the pages it closes. */
    private fun overviewTap(i: Int) {
        val page = a.pages.getOrNull(i)
        when {
            page == null -> a.homeMenus.closeMenu()
            page === plusPage -> addPageInPlace(page)
            else -> { a.workspace.snapTo(i); a.homeMenus.closeMenu() }
        }
    }

    /**
     * "+" tapped: as on One UI, its card becomes the new empty page where it is (its "+" fades out) and a new "+" card
     * slides in after it, all without a rebuild, so nothing on screen blinks.
     */
    private fun addPageInPlace(page: CellLayout) {
        plusPage = null
        page.contentDescription = null
        page.setOnLongClickListener { a.homeMenus.homeOverview(); true }
        page.foreground?.let { plus ->
            ValueAnimator.ofInt(255, 0).apply {
                duration = 180
                addUpdateListener { plus.alpha = it.animatedValue as Int }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) { if (page.foreground === plus) page.foreground = null }
                })
                start()
            }
        }
        a.looks["pages"] = a.pages.size.toString()
        a.indicator.count = a.pages.size
        page.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        addPlusPage()
        // Its card and "+" fade in; not the view's alpha, which the shrunk workspace sets on every layout.
        plusPage?.let { next ->
            val card = pageCard().also { next.background = it }
            val plus = next.foreground
            ValueAnimator.ofInt(0, 255).apply {
                duration = 260
                startDelay = 80
                addUpdateListener { card.alpha = it.animatedValue as Int; plus?.alpha = it.animatedValue as Int }
                start()
            }
            card.alpha = 0; plus?.alpha = 0
        }
    }

    private fun overviewHold(i: Int) {
        val page = a.pages.getOrNull(i)
        if (page == null || page === plusPage || a.locked) return
        page.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        (dropBar.getChildAt(0) as TextView).text = a.getString(R.string.page_remove)
        dropBar.getChildAt(1).visibility = View.GONE
        dropBar.visibility = View.VISIBLE
        val s = a.workspace.scaleX
        page.startDragAndDrop(null, object : View.DragShadowBuilder(page) {
            override fun onProvideShadowMetrics(size: Point, touch: Point) {
                size.set((page.width * s).toInt(), (page.height * s).toInt())
                touch.set(size.x / 2, size.y / 2)
            }
            override fun onDrawShadow(c: android.graphics.Canvas) { c.scale(s, s); page.draw(c) }
        }, PageMove(i), View.DRAG_FLAG_OPAQUE)
        page.alpha = 0.3f
    }

    /** A page's drag over [cell]: holding it near an edge turns the page; dropping it there moves it. */
    private fun pageDrag(cell: CellLayout, e: DragEvent, m: PageMove): Boolean {
        if (isDock(cell.page)) return false
        when (e.action) {
            DragEvent.ACTION_DRAG_LOCATION -> edgeFlip(a.workspace, e.x, cell.width)
            DragEvent.ACTION_DROP -> if (cell !== plusPage && cell.page != m.index) a.root.post { a.movePageTo(m.index, cell.page) { a.workspace.snapTo(cell.page) } }
            DragEvent.ACTION_DRAG_ENDED -> { dropBar.visibility = View.GONE; a.pages.forEach { it.alpha = 1f } }
        }
        return true
    }

    /** Runs [then] once the views a rebuild made are measured, before their first frame. */
    fun afterRebuild(then: () -> Unit) {
        val rebuilt = a.workspace
        rebuilt.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                rebuilt.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (a.workspace === rebuilt && !a.isDestroyed) then()
            }
        })
    }

    /** Gives a touch on the overview's shade to the shrunk pages, in their own coordinates. */
    fun toWorkspace(ev: MotionEvent) {
        val e = MotionEvent.obtain(ev)
        var x = 0
        var y = 0
        var v: View = a.workspace
        while (v !== a.root) { x += v.left; y += v.top; v = v.parent as View }
        e.offsetLocation(-x.toFloat(), -y.toFloat())
        e.transform(android.graphics.Matrix().also { a.workspace.matrix.invert(it) })
        a.workspace.dispatchTouchEvent(e)
        e.recycle()
    }

    fun newPage(i: Int) = CellLayout(a, a.looks.cols * a.sub, a.looks.rows * a.sub, i).apply {
        setOnDragListener(dropListener(this))
        setOnLongClickListener { a.homeMenus.homeOverview(); true }
        held = { a.items.filter { it !in a.views } }
    }

    /** The empty page a drag adds after the last one ([addDragPage]), the home page it started on, and whether it landed on home. */
    private var dragPage: CellLayout? = null
    private var dragFrom: Int? = null
    private var dropped = false

    /**
     * As Launcher3: while dragging, an empty page waits after the last one (up to the Pages setting's 9), unless the
     * last one is empty already. [settleDragPages] keeps it if something lands on it.
     */
    // ponytail: the page dots don't count it until the drop; Launcher3 shows it as one more dot while dragging.
    private fun addDragPage() {
        if (dragPage != null || a.pages.size >= 9 || a.pages.last().childCount == 0) return
        newPage(a.pages.size).also {
            it.background = pageCard().apply { alpha = (spring * 255).toInt() }
            dragPage = it; a.pages = a.pages + it; a.workspace.addView(it)
        }
    }

    /** After a drag: [settlePages]'s rules, then a rebuild only when the page count changes. */
    private fun settleDragPages() {
        val extra = dragPage
        dragPage = null
        val (kept, n, gone) = settlePages(a.items, a.looks.pages, if (dropped) dragFrom else null)
        if (n == a.looks.pages && gone < 0) {
            extra?.let { a.pages = a.pages - it; a.workspace.removeView(it); a.workspace.snapTo(a.workspace.current) }
            return
        }
        a.items = kept.toMutableList()
        val default = a.looks.int("defaultPage")
        if (gone in 0 until default) a.looks["defaultPage"] = (default - 1).toString()
        val show = a.workspace.current - if (gone in 0 until a.workspace.current) 1 else 0
        a.looks["pages"] = n.toString()
        a.save()
        a.rebuild()
        a.workspace.snapTo(show, animate = false)
    }

    /** Holding a drag near the left/right edge flips to the neighbouring page. */
    fun edgeFlip(ws: Workspace, x: Float, width: Int) {
        val now = SystemClock.uptimeMillis()
        if (now - lastEdgeFlip < 700 || ws.childCount < 2) return
        val edge = width * 0.06f
        if (x < edge) ws.snapTo(ws.current - 1)
        else if (x > width - edge) ws.snapTo(ws.current + 1)
        else return
        lastEdgeFlip = now
    }

    fun appOf(p: Any?): App? = when (p) {
        is App -> p
        is GroupChild -> p.app
        is Item -> if (p.isApp) a.repo.apps[p.appKey] else null
        is FolderChild -> if (p.child.isApp) a.repo.apps[p.child.appKey] else null
        else -> null
    }

    /** The payload as a 1×1 item that can live in a folder, or null (widgets, folders). */
    fun asChild(p: Any?): Item? = when (p) {
        is Item -> if (p.isBlock || p.folder) null else p.copy(page = 0, x = 0, y = 0, w = 1, h = 1)
        is App -> Item(0, 0, 0, pkg = p.pkg, cls = p.cls, user = p.serial)
        is GroupChild -> asChild(p.app)
        is FolderChild -> p.child
        is ShortcutInfo -> Item(0, 0, 0, pkg = p.`package`, shortcutId = p.id, user = a.repo.serial(p.userHandle), label = p.shortLabel?.toString() ?: "")
        else -> null
    }

    /** A drawer folder as a new home folder with the same apps (a copy: the drawer keeps its own). */
    fun groupItem(g: Group): Item? {
        val apps = g.apps.mapNotNull { a.repo.apps[it] }.map { Item(0, 0, 0, a.sub, a.sub, pkg = it.pkg, cls = it.cls, user = it.serial) }
        // As everywhere on home: a folder of one is that app, and there's no empty folder.
        return when (apps.size) { 0 -> null; 1 -> apps[0]; else -> Item(0, 0, 0, a.sub, a.sub, folder = true, label = g.name, children = apps) }
    }

    /** The payload as a home item, a drawer folder included. */
    private fun asItem(p: Any?) = if (p is Group) groupItem(p) else p as? Item

    /** Where dropping [payload] at ([px], [py]) would place it (grid units), or null when it can't go there. */
    private fun landing(cell: CellLayout, px: Float, py: Float, payload: Any?): Rect? {
        val moving = asItem(payload)
        if (moving == null && asChild(payload) == null) return null
        if (isDock(cell.page) && moving?.isBlock == true) return null
        val unit = if (isDock(cell.page)) 1 else a.sub
        val w = if (moving?.isBlock == true) moving.w else unit
        val h = if (moving?.isBlock == true) moving.h else unit
        val (gx, gy) = cell.toGrid(px, py)
        val x = (gx - w / 2f + 0.5f).toInt().coerceIn(0, maxOf(0, cell.cols - w))
        val y = (gy - h / 2f + 0.5f).toInt().coerceIn(0, maxOf(0, cell.rows - h))
        val overlap = a.looks.on("widgetOverlap") && moving?.isBlock == true
        if (!overlap && !cell.isFree(x, y, w, h, ignore = moving?.let { a.views[it] })) return null
        return Rect(x, y, x + w, y + h)
    }

    private fun drop(cell: CellLayout, px: Float, py: Float, payload: Any?): Boolean {
        if (!dragMoved) return false
        val child = asChild(payload)
        val target = a.mergeTarget(cell, px, py, payload)
        if (child != null && target != null) {
            if (!a.mergeIntoFolder(target, child, payload)) return false
            dropped = true
            if (child.isShortcut) a.syncPins(child.pkg, child.user) // as the plain drop below: an unpinned shortcut stops resolving
            return true
        }
        a.widgetFlow.stackTarget(cell, px, py, payload)?.let { onto ->
            val moving = payload as Item
            if (onto !in a.items || !a.items.remove(moving)) return false
            dropped = true
            a.items[a.items.indexOf(onto)] = onto.withWidgets(onto.widgetIds + moving.widgetIds)!!
            a.root.confirm()
            a.save()
            a.render()
            return true
        }
        val spot = landing(cell, px, py, payload) ?: return false
        val placed = (asItem(payload) ?: child ?: return false).copy(page = cell.page, x = spot.left, y = spot.top, w = spot.width(), h = spot.height())
        // Gone from where it came from (its app was removed, its folder changed) since the drag began: placing it would make a second one.
        if (!a.detach(payload)) return false
        dropped = true
        a.items += placed
        a.root.tick()
        if (placed.isShortcut) a.syncPins(placed.pkg, placed.user)
        a.save()
        a.render()
        return true
    }
}
