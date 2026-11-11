package dev.vulcanlauncher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/** Wraps a widget so a long-press reaches us even though the widget handles its own touches. */
open class WidgetFrame(context: Context) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var fired = false
    private val longPress = Runnable { fired = true; performLongClick() }

    // In dispatch, not intercept: a touch on a part of the widget no child takes never reaches intercept again after
    // DOWN, so its UP didn't cancel the timer and a plain tap became a failed drag that left the widget hidden (#60).
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fired = false; downX = ev.x; downY = ev.y
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE ->
                if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) removeCallbacks(longPress)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent) = fired
}

/**
 * Widgets stacked in one spot: one shows, and a sideways swipe brings the next, wrapping round. Up and down stay with
 * the widget (a scrolling list) and the home screen. [shown] starts showing; [onFlip] gets the index now shown.
 * [dot]: the dots' colour.
 */
class WidgetStack(context: Context, private val frames: List<View>, private var shown: Int, private val dot: Int, private val onFlip: (Int) -> Unit) : WidgetFrame(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var startX = 0f
    private var startY = 0f
    private var dragging = false
    private var anim: ValueAnimator? = null

    init {
        frames.forEachIndexed { i, f ->
            (f.parent as? ViewGroup)?.removeView(f)
            // A cached frame may still hold home's drag listener from when it stood alone; the stack takes the long-press.
            f.setOnLongClickListener(null)
            f.isLongClickable = false
            f.translationX = 0f
            f.visibility = if (i == shown) VISIBLE else INVISIBLE
            addView(f, LayoutParams(-1, -1))
        }
        setWillNotDraw(false)
    }

    /** How far the shown widget is pulled sideways; the neighbour it reveals follows it in. */
    private var offset = 0f
        set(v) {
            field = v
            val n = frames.size
            val next = if (v < 0) (shown + 1) % n else (shown - 1 + n) % n
            frames.forEachIndexed { i, f ->
                f.translationX = if (i == shown) v else v + if (v < 0) width else -width
                f.visibility = if (i == shown || i == next && v != 0f) VISIBLE else INVISIBLE
            }
        }

    private fun track(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { anim?.end(); startX = ev.x; startY = ev.y; dragging = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - startX
                if (!dragging && abs(dx) > touchSlop && abs(dx) > abs(ev.y - startY)) {
                    dragging = true
                    startX += if (dx > 0) touchSlop else -touchSlop // without jumping the slop it took to decide
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) offset = ev.x - startX
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                // A quick flick sends few moves: where it let go counts, and a short flick flips as a long drag does.
                val up = ev.actionMasked == MotionEvent.ACTION_UP
                if (up) offset = ev.x - startX
                settle(up, flick = up && ev.eventTime - ev.downTime < 250)
                return true
            }
        }
        return dragging
    }

    override fun onInterceptTouchEvent(ev: MotionEvent) = track(ev) || super.onInterceptTouchEvent(ev)

    // DOWN on a part of the widget no child takes lands here: taken, so a sideways swipe from there still flips. Taps
    // belong to the widget's own views; TalkBack flips with the scroll actions below.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean { track(ev); return true }

    /** Shows widget [to] at once (TalkBack's scroll actions, which its touch exploration needs instead of the swipe). */
    private fun flipTo(to: Int) {
        anim?.end()
        shown = (to + frames.size) % frames.size
        offset = 0f
        invalidate()
        onFlip(shown)
        announceForAccessibility(context.getString(R.string.page_of, shown + 1, frames.size))
    }

    override fun getAccessibilityClassName(): CharSequence = android.widget.HorizontalScrollView::class.java.name

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isScrollable = true
        info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, args: android.os.Bundle?): Boolean = when (action) {
        android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { flipTo(shown + 1); true }
        android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { flipTo(shown - 1); true }
        else -> super.performAccessibilityAction(action, args)
    }

    /** A fifth of the width, or a [flick], flips to the neighbour; less springs back. */
    private fun settle(released: Boolean, flick: Boolean) {
        val dir = if (offset < 0) 1 else -1
        val flip = released && (flick || abs(offset) > width / 5f)
        anim = ValueAnimator.ofFloat(offset, if (flip) -dir * width.toFloat() else 0f).apply {
            duration = 200
            addUpdateListener { offset = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    anim = null
                    if (!flip) return
                    shown = (shown + dir + frames.size) % frames.size
                    offset = 0f
                    invalidate()
                    onFlip(shown)
                }
            })
            start()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val r = 3 * dp
        val gap = 10 * dp
        val x0 = width / 2f - (frames.size - 1) * gap / 2
        for (i in frames.indices) {
            paint.color = if (i == shown) dot else dot and 0xFFFFFF or 0x66000000
            canvas.drawCircle(x0 + i * gap, height - r - 2 * dp, r, paint)
        }
    }
}
