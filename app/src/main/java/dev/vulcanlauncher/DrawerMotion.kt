package dev.vulcanlauncher

import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Whether a released drawer pull ends open: flung up faster than [fling] (px/s, upward positive), or past [threshold]
 * unless flung down. A pull up commits at 30% (Launcher3: 40%), a pull down closes below 70%.
 */
fun drawerOpens(progress: Float, vy: Float, fling: Float, threshold: Float = 0.5f) = vy > fling || vy > -fling && progress > threshold

/**
 * Launcher3's swipe-release duration (BaseSwipeDetector.calculateDuration), on an 800 ms base instead of its 1200 for a
 * quicker finish: [v] release speed in px/ms, [remaining] progress left.
 */
fun swipeDuration(v: Float, remaining: Float) = maxOf(80L, (800f / maxOf(2f, abs(0.5f * v)) * maxOf(0.2f, remaining)).toLong())

/** Launcher3's release curve (scrollInterpolatorForVelocity): quintic ease-out after a fast fling, else cubic. */
private val SCROLL_CUBIC = android.view.animation.Interpolator { t -> val u = t - 1; u * u * u + 1 }

/** [from]..[to] of [p] as 0..1, clamped (Launcher3's clampToProgress over a linear curve). */
private fun span(p: Float, from: Float, to: Float) = ((p - from) / (to - from)).coerceIn(0f, 1f)

/** How the drawer opens and closes: following a finger up from home or down from the list, then animating the rest. */
internal class DrawerMotion(private val a: MainActivity, private val d: Drawer) {
    /** How open the drawer is: 0 closed, 1 open. Follows the finger while it's pulled, then animates the rest. */
    var progress = 0f
        private set
    var anim: android.animation.ValueAnimator? = null
    /** Where a running [anim] is heading: true for open. */
    var settlingOpen = false
        private set
    /** A finger is moving the drawer (home's swipe up, or its own pull down). */
    var held = false
        private set

    /** Finger travel that fully opens or closes the drawer (Launcher3's phones: 300 dp; shorter here, so it's quicker). */
    private fun travel() = 220 * a.dp

    /** A VelocityTracker sample in screen coordinates: the list moves with the sheet, so its own would halve a flick. */
    fun track(tracker: android.view.VelocityTracker?, ev: MotionEvent) {
        tracker?.let {
            val sample = MotionEvent.obtain(ev)
            sample.setLocation(ev.rawX, ev.rawY)
            it.addMovement(sample)
            sample.recycle()
        }
    }

    /**
     * A finger pulled down on the list, the search bar, the field or the frequent row: the drawer follows it and
     * closes on release, or springs back open. [start] gates the pull (the list only pulls from its top); [search], on
     * the list alone, turns a longer pull into search instead when 'pull to search' is on.
     */
    inner class PullDown(
        private val start: () -> Boolean = { true },
        private val search: (() -> Unit)? = null,
    ) {
        /** The drawer is following this touch. */
        var closing = false
            private set
        /** This event started the pull, so the view's own touch must be cancelled. */
        var started = false
            private set
        private var downY = 0f
        private var velocity: android.view.VelocityTracker? = null
        /** The finger that went down last, and so owns a pull it starts. */
        private var owner = -1

        fun handle(ev: MotionEvent): Boolean {
            started = false
            // A second finger on another view sharing this pull (the bar while the row is pulled) arrives as its own DOWN:
            // taken as a new gesture, it reset the pull, the first finger's UP then never released it, and [held] froze
            // the drawer half-open. It's swallowed instead, and the first finger's pull carries on.
            if (closing && ev.getPointerId(ev.actionIndex) != owner) return true
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                owner = ev.getPointerId(0)
                // Screen coordinates: the list moves with the sheet, so its own would halve the pull.
                closing = false; downY = ev.rawY
                velocity?.recycle(); velocity = android.view.VelocityTracker.obtain()
            }
            track(velocity, ev)
            if (!closing) {
                if (ev.actionMasked == MotionEvent.ACTION_MOVE && ev.rawY - downY > 24 * a.dp && start()) {
                    if (search != null && a.looks.on("pullToSearch")) {
                        if (ev.rawY - downY > 96 * a.dp) { downY = Float.MAX_VALUE; search() }
                    } else {
                        closing = true; started = true; held = true; downY = ev.rawY; stop()
                        // As Launcher3, the keyboard goes as the drag down starts, not once the drawer is gone (#73).
                        d.hideKeyboard()
                    }
                }
                if (!closing) {
                    if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                        velocity?.recycle(); velocity = null
                    }
                    return false
                }
            }
            // On release too: the finger's last position decides whether it's past the threshold, even if the last move dropped.
            if (ev.actionMasked == MotionEvent.ACTION_MOVE || ev.actionMasked == MotionEvent.ACTION_UP)
                setProgress(1 - (ev.rawY - downY) / travel())
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                velocity?.computeCurrentVelocity(1000)
                release(-(velocity?.yVelocity ?: 0f), 0.7f)
                velocity?.recycle(); velocity = null
                closing = false; held = false
            }
            return true
        }
    }

    /**
     * Opaque panels hand home over, as One UI does: home lifts and fades out as the drawer covers it, so it isn't
     * blurred every frame. Glass and housing keep home showing (blurred or dimmed) behind them.
     */
    private val handover = !d.housing && !d.glass

    /**
     * The drawer at [p], staged as Launcher3's swipe to all apps on phones but earlier: the sheet rises [travel] with the
     * finger, its background fading in over 5–30% and the apps over 25–60%, while home sinks back and is gone by 30%.
     */
    private fun setProgress(p: Float) {
        progress = p.coerceIn(0f, 1f)
        d.root.visibility = View.VISIBLE
        d.root.alpha = span(progress, 0.05f, 0.3f)
        val apps = span(progress, 0.25f, 0.6f)
        for (i in 0 until d.root.childCount) d.root.getChildAt(i).alpha = apps
        d.root.translationY = (1 - progress) * travel()
        val home = span(progress, 0f, 0.3f)
        if (d.glass && a.glassBlur) d.root.semBlur((40 * a.dp * home).toInt(), a.glassTint(true), 0f)
        if (a.overlay == null) a.setDepth(home, blur = !handover)
        if (handover) { a.content.alpha = 1 - home; a.content.translationY = -home * 48 * a.dp }
    }

    /**
     * Animates to open or closed from wherever the drawer is. [speed]: the release speed (px/s, upward positive) after a
     * finger pull, timed as Launcher3 does; null for a button or Back.
     */
    fun settle(open: Boolean, speed: Float? = null) {
        stop()
        val to = if (open) 1f else 0f
        val left = kotlin.math.abs(to - progress)
        // Already there: finish now, so the keyboard isn't held back by an animation that moves nothing.
        if (left == 0f) { setProgress(to); if (!open) gone() else d.opened(); return }
        val pxMs = (speed ?: 0f) / 1000
        settlingOpen = open
        anim =android.animation.ValueAnimator.ofFloat(progress, to).apply {
            duration = if (speed != null) swipeDuration(pxMs, left) else ((if (open) 260 else 200) * maxOf(0.4f, left)).toLong()
            interpolator = if (speed == null) android.view.animation.DecelerateInterpolator(1.6f) else if (abs(pxMs) > 10) SCROLL else SCROLL_CUBIC
            addUpdateListener { setProgress(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(x: android.animation.Animator) { if (anim === x) { anim = null; if (!open) gone() else d.opened() } }
            })
            start()
        }
    }

    /** Stops a running open/close animation where it is (without its end action). */
    fun stop() { val old = anim; anim = null; old?.cancel() }

    fun gone() {
        d.closed()
        d.endTabSwipe()
        d.root.alpha = 1f
        for (i in 0 until d.root.childCount) d.root.getChildAt(i).alpha = 1f
        // Every close ends here, whatever started it: the keyboard mustn't stay up over home (#73).
        // Before GONE, which takes the focus and leaves the keyboard behind.
        d.hideKeyboard()
        d.root.visibility = View.GONE
        d.root.semBlur(0, 0, 0f)
        a.content.alpha = 1f
        a.content.translationY = 0f
        // A drag in progress still routes events to a GONE view by its bounds, so move it off-screen too.
        d.root.translationY = d.root.height.toFloat()
        progress = 0f
        held = false
    }

    /**
     * Home's swipe up, while the finger is still down: [up] px pulled so far. On release ([end]) the drawer opens
     * if flung up ([vy] px/s, upward positive) or pulled past 30% of [travel]. Returns false for a pull that starts downward.
     */
    fun pull(up: Float, vy: Float, end: Boolean): Boolean {
        // A menu or overlay held open by this same finger: the swipe is swallowed, not opening the drawer over it.
        if (!d.visible && (a.homeMenus.menu != null || a.overlay != null)) return true
        if (!end && !d.visible && up <= 0f) return false
        // Once per gesture: a close still animating (held is false until this pull moves) is forgotten too.
        if (!d.visible || !held) d.prepare()
        stop()
        held = !end
        // On release too: the last MOVE can lag well behind where the finger lifted when input comes in sparse.
        setProgress(up / travel())
        if (end) {
            val open = release(vy, 0.3f)
            if (open && !armed) d.root.tick() // a fling that never crossed the line still gets its tick
            armed = false
        } else {
            // The tick comes as the pull crosses the line where letting go opens the drawer, not on release (#97).
            if (!armed && progress >= 0.3f) { armed = true; d.root.tick() } else if (armed && progress < 0.3f) armed = false
        }
        return true
    }

    /** Whether this pull has passed the open line and ticked for it. */
    private var armed = false

    /** Ends a finger-driven pull: opens if flung up or past [threshold], unless flung down. Returns whether it opens. */
    private fun release(vy: Float, threshold: Float): Boolean {
        // Launcher3 counts anything over 1 dp/ms as a fling.
        val open = drawerOpens(progress, vy, 1000 * a.dp, threshold)
        settle(open, vy)
        return open
    }
}
