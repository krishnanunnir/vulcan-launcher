package dev.vulcanlauncher

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

/** [list] with the item at [from] taken out and put back at [to]. */
fun <T> moveItem(list: List<T>, from: Int, to: Int): List<T> =
    if (from !in list.indices || to !in list.indices) list else list.toMutableList().apply { add(to, removeAt(from)) }

/**
 * Where a row dragged from slot [from] lands, moved [dy] px: it takes a row's place once its leading edge (the bottom
 * going down, the top going up) passes that row's middle. [tops] and [heights] are the rows' places before the drag.
 */
fun reorderTarget(tops: List<Int>, heights: List<Int>, from: Int, dy: Float): Int {
    val top = tops[from] + dy
    val bottom = top + heights[from]
    var to = from
    while (to + 1 < tops.size && bottom > tops[to + 1] + heights[to + 1] / 2f) to++
    while (to - 1 >= 0 && top < tops[to - 1] + heights[to - 1] / 2f) to--
    return to
}

/** [groups] with the [type] groups ("tab" or "folder") reordered, the one at [from] moved to [to]; the other type keeps its places. */
fun reorderGroups(groups: List<Group>, type: String, from: Int, to: Int): List<Group> {
    val order = moveItem(groups.filter { it.type == type }, from, to).iterator()
    return groups.map { if (it.type == type) order.next() else it }
}

/** [groups] with [keys] taken out of every group but [keep]: picking apps for one group moves them there. */
fun withoutApps(groups: List<Group>, keys: Set<String>, keep: String): List<Group> =
    groups.map { if (it.name == keep) it else it.copy(apps = it.apps.filter { k -> k !in keys }) }

/**
 * Drag to reorder: [rows] (children of one vertical parent, top to bottom, maybe with dividers between) follow the
 * finger once [begin] is called from a touch on one of them, the rows it passes making room. Let go, and [onDrop]
 * gets the old and new index. Each row also offers Move up and Move down to accessibility services.
 */
class Reorder(private val a: VulcanActivity, private val rows: List<View>, private val onDrop: (from: Int, to: Int) -> Unit) {
    private var from = -1
    private var to = -1
    private var startY = 0f
    private var tops = listOf<Int>()
    private var heights = listOf<Int>()
    /** How far the rows that make room move: the dragged row's slot, including the gap to the next row. */
    private var slot = 0
    val dragging get() = from >= 0

    init {
        rows.forEachIndexed { i, row ->
            row.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(v: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(v, info)
                    if (i > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_move_up, a.getString(R.string.move_up)))
                    if (i < rows.size - 1) info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_move_down, a.getString(R.string.move_down)))
                }
                override fun performAccessibilityAction(v: View, action: Int, args: android.os.Bundle?): Boolean = when (action) {
                    R.id.action_move_up -> { onDrop(i, i - 1); true }
                    R.id.action_move_down -> { onDrop(i, i + 1); true }
                    else -> super.performAccessibilityAction(v, action, args)
                }
            }
        }
    }

    /** Starts dragging [row] from the finger at [rawY]. */
    fun begin(row: View, rawY: Float) {
        from = rows.indexOf(row).takeIf { it >= 0 } ?: return
        to = from
        startY = rawY
        tops = rows.map { it.top }
        heights = rows.map { it.height }
        slot = if (from + 1 < rows.size) tops[from + 1] - tops[from] else if (from > 0) tops[from] - tops[from - 1] else heights[from]
        row.parent?.requestDisallowInterceptTouchEvent(true)
        row.animate().scaleX(1.03f).scaleY(1.03f).translationZ(8 * a.dp).setDuration(120).start()
        row.tick()
    }

    /** Feeds the rest of the touch; true while a drag takes it. */
    fun onTouch(ev: MotionEvent): Boolean {
        if (from < 0) return false
        val row = rows[from]
        // Kept within the first and last rows' span.
        val dy = (ev.rawY - startY).coerceIn((tops.first() - tops[from]).toFloat(), (tops.last() + heights.last() - tops[from] - heights[from]).toFloat())
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                row.translationY = dy
                val t = reorderTarget(tops, heights, from, dy)
                if (t != to) { to = t; row.tick() }
                rows.forEachIndexed { i, r ->
                    if (i == from) return@forEachIndexed
                    val shift = when {
                        i in (from + 1)..to -> -slot
                        i in to until from -> slot
                        else -> 0
                    }
                    if (r.translationY != shift.toFloat()) r.animate().translationY(shift.toFloat()).setDuration(150).start()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val f = from; val t = to
                from = -1
                // Settle into the slot it was dropped on, then hand the new order over (which rebuilds the rows).
                val land = (tops[t] - tops[f] + if (t > f) heights[t] - heights[f] else 0).toFloat()
                row.animate().translationY(land).scaleX(1f).scaleY(1f).translationZ(0f).setDuration(150).withEndAction {
                    rows.forEach { it.translationY = 0f }
                    if (t != f) onDrop(f, t)
                }.start()
            }
        }
        return true
    }

    /** A drag handle for [row]: touching it starts the drag at once. */
    @SuppressLint("ClickableViewAccessibility")
    fun handle(row: View): View = a.iconButton("drag", a.getString(R.string.drag_to_reorder)) {}.apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO // the row carries Move up/down
        setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) begin(row, ev.rawY)
            onTouch(ev)
        }
    }
}
