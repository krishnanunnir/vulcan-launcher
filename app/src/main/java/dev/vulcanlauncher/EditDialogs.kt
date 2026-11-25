package dev.vulcanlauncher

import android.content.Intent
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import org.json.JSONObject

// Editing one item or app: its label, icon and swipe action, and the icon pickers.


/**
 * Label, icon and (for home items) swipe-up action. Empty label or icon means "use the default".
 * [preview] draws an icon value ("" is the default icon).
 */
fun MainActivity.editDialog(title: String, label: String, defaultLabel: String, icon: String?, swipe: String?,
                            preview: (String) -> Drawable?, folder: Boolean = false, defaultIcon: String = "",
                            onSave: (String, String, String) -> Unit) {
    var iconV = icon ?: ""
    var swipeV = swipe ?: ""
    val pad = (24 * dp).toInt()
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, (8 * dp).toInt(), pad, 0) }
    val name = field(label, defaultLabel.ifEmpty { getString(R.string.label) })
    // As Nova: the icon sits beside the name; tap it to change it.
    val iconView = if (icon != null) ImageView(this).apply {
        contentDescription = getString(R.string.icon)
        val p = (8 * dp).toInt()
        setPadding(p, p, p, p)
        background = pressable(rounded(m3.surfaceHighest, 16 * dp), 16 * dp)
    } else null
    fun show() { iconView?.setImageDrawable(preview(iconV)) }
    // A picked image is shaped like a legacy icon; this one can opt out, as Nova offers (#92).
    val reshapeBox = android.widget.FrameLayout(this)
    fun reshapeRow() {
        reshapeBox.removeAllViews()
        val (uri, reshape) = imageIcon(iconV) ?: return
        if (looks.str("reshape") == "off") return
        reshapeBox.addView(checkRow(getString(R.string.reshape_icon), reshape) { on ->
            iconV = if (on) "uri:$uri" else "uriraw:$uri"; show()
        })
    }
    show(); reshapeRow()
    iconView?.setOnClickListener { pickIcon { iconV = it.ifEmpty { defaultIcon }; show(); reshapeRow() } }
    box.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        if (iconView != null) addView(iconView, LinearLayout.LayoutParams((56 * dp).toInt(), (56 * dp).toInt()).apply { marginEnd = (16 * dp).toInt() })
        addView(name, LinearLayout.LayoutParams(0, -2, 1f))
    })
    box.addView(reshapeBox)
    if (swipe != null) {
        val holder = android.widget.FrameLayout(this)
        fun row() {
            holder.removeAllViews()
            // A folder keeps "none", so it can opt out of the global Swipe to open; "" follows that setting.
            val shown = swipeV.ifEmpty { if (folder && looks.on("folderSwipe")) "folder" else "none" }
            holder.addView(listItem(symbol("gesture"), getString(R.string.swipe_up_action), actionName(shown, repo::appLabel)) {
                pickAction(getString(R.string.swipe_up_action), folder) { swipeV = if (it == "none" && !folder) "" else it; row() }
            })
        }
        row()
        // The row's own 16 dp inset lines its icon up with the dialog's 24 dp edge.
        box.addView(holder, LinearLayout.LayoutParams(-1, -2).apply { setMargins((-16 * dp).toInt(), (8 * dp).toInt(), -pad, 0) })
    }
    sheet().setTitle(title).setView(box)
        .setPositiveButton(R.string.save) { _, _ -> onSave(name.text.toString().trim(), iconV, swipeV) }
        .setNegativeButton(R.string.cancel, null).show()
}

/**
 * [replace] puts the edited item back: on the home screen by default, or into its folder. [swipes] false hides the
 * swipe action, for an app in a folder, whose icons take no swipes; its own setting is kept.
 */
fun MainActivity.editItem(item: Item, swipes: Boolean = true, replace: (Item) -> Unit = { new ->
    items.indexOf(item).takeIf { it >= 0 }?.let { replace(it, new) }
}) {
    val default = when {
        item.folder -> getString(R.string.folder)
        item.isApp -> repo.appLabel(item.appKey) ?: ""
        else -> resolve(item.copy(label = ""))?.first?.toString() ?: ""
    }
    // An app's Default is its own icon: without it, Default here fell back to an icon set from the drawer's Edit, so
    // that one could never be undone from the home screen (#92). Saved as no override, the drawer's icon cleared.
    val own = if (item.isApp) "app:${item.pkg}/${item.cls}" else ""
    editDialog(getString(if (item.folder) R.string.edit_folder else R.string.edit), item.label, default, item.icon, item.swipe.takeIf { swipes },
        { ic -> resolve(item.copy(icon = ic))?.second }, item.folder, own) { l, ic, sw ->
        if (own.isNotEmpty() && ic == own) repo.customs.optJSONObject(item.appKey)?.takeIf { it.optString("icon").isNotEmpty() }?.let {
            it.put("icon", ""); store.putObj("custom", repo.customs); repo.loadApps(listOf(item.pkg)); drawer.refresh()
        }
        replace(item.copy(label = if (item.folder) l.ifEmpty { getString(R.string.folder) } else l, icon = if (ic == own) "" else ic, swipe = if (swipes) sw else item.swipe))
    }
}

/** Drawer edits apply to the app everywhere it has no home-screen override. */
fun MainActivity.editApp(app: App) {
    val c = repo.customs.optJSONObject(app.key)
    editDialog(getString(R.string.edit_app), c?.optString("label") ?: "", app.info.label.toString(), c?.optString("icon") ?: "", null,
        { ic -> icons.get(app.pkg, app.cls, ic) { app.info.getBadgedIcon(0) } }) { l, ic, _ ->
        repo.customs.put(app.key, JSONObject().put("label", l).put("icon", ic))
        store.putObj("custom", repo.customs)
        repo.loadApps(listOf(app.pkg))
        drawer.refresh()
        render()
    }
}

fun MainActivity.pickIcon(onPicked: (String) -> Unit) {
    val packs = iconPacks(this)
    val rows = listOf(symbol("refresh") to getString(R.string.icon_default_option)) +
        packs.map { (pkg, name) -> runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull() to getString(R.string.icon_from_pack, name) } +
        (symbol("apps") to getString(R.string.icon_from_app)) + (symbol("image") to getString(R.string.icon_from_gallery))
    pickList(getString(R.string.icon), rows) { i ->
        when {
            i == 0 -> onPicked("")
            i <= packs.size -> pickPackIcon(packs[i - 1].first, onPicked)
            i == packs.size + 1 -> pickApp(getString(R.string.icon_from_app)) { onPicked("app:${it.pkg}/${it.cls}") }
            else -> {
                onPickedImage = { onPicked("uri:$it") }
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), MainActivity.REQ_ICON)
            }
        }
    }
}

/** Opens [pkg]'s icon grid; [onPicked] gets the icon chosen there. */
private fun MainActivity.pickPackIcon(pkg: String, onPicked: (String) -> Unit) {
    if (loadIconPack(this, pkg) == null) { toast(getString(R.string.icon_pack_unreadable)); return }
    IconGridActivity.onPicked = onPicked
    // Its own task, as settings: as a dialog in home's task, the Home gesture faded it out and back in together with
    // home before home was told to close it (#100).
    startActivity(Intent(this, IconGridActivity::class.java).putExtra("pack", pkg)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
}

/** A full-screen grid of a pack's icons under a search bar. Icons load off the main thread, so scrolling stays smooth. */
class IconGridActivity : VulcanActivity() {
    override val home get() = checkNotNull(MainActivity.running?.get())
    private val loader = java.util.concurrent.Executors.newSingleThreadExecutor()
    /** Who asked for this pick; a later grid has its own. */
    private var picked: ((String) -> Unit)? = null

    override fun onCreate(state: android.os.Bundle?) {
        super.onCreate(state)
        val h = MainActivity.running?.get()
        val pkg = intent.getStringExtra("pack").orEmpty()
        val pack = loadIconPack(this, pkg) // parsed already by pickPackIcon
        picked = onPicked
        // Brought back alone after the process died: nobody is waiting for the pick.
        if (h == null || h.isDestroyed || picked == null || pack == null) { finish(); return }
        store = h.store
        looks = h.looks
        applyOrientation()
        recolor()
        bars()
        val all = pack.names
        var shown = all
        val cell = (72 * dp).toInt()
        val pad = (10 * dp).toInt()
        // ponytail: ~150 icons kept (a few screens' worth, ~10 MB drawn at cell size); a pack's thousands are redrawn as they scroll back in.
        val cache = android.util.LruCache<String, Drawable>(150)
        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(p: Int) = shown[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup) = ((convert as? ImageView) ?: ImageView(this@IconGridActivity).apply {
                layoutParams = android.widget.AbsListView.LayoutParams(-1, cell)
                setPadding(pad, pad, pad, pad)
                background = pressable(null, 16 * dp)
            }).apply {
                val n = shown[p]
                tag = n
                contentDescription = n
                setImageDrawable(cache.get(n))
                if (cache.get(n) == null) loader.execute {
                    if (tag != n) return@execute // scrolled past before its turn
                    val d = pack.drawable(n)?.let { flatIcon(this@IconGridActivity, it, cell - 2 * pad) } ?: return@execute
                    cache.put(n, d)
                    post { if (tag == n) setImageDrawable(d) }
                }
            }
        }
        val grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = cell
            this.adapter = adapter
            val pad = (12 * dp).toInt()
            setPadding(pad, 0, pad, pad)
            clipToPadding = false
            setOnItemClickListener { _, _, p, _ -> picked?.invoke("pack:$pkg:${shown[p]}"); finish() }
        }
        val filter = EditText(this).apply {
            hint = resources.getQuantityString(R.plurals.filter_icons, all.size, all.size)
            setSingleLine()
            textSize = 16f
            setTextColor(m3.onSurface)
            setHintTextColor(m3.onSurfaceVariant)
            background = null
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { shown = all.filter { it.contains(s.toString(), true) }; adapter.notifyDataSetChanged() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(m3.surface)
            // M3 search bar: back, then the filter.
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(m3.surfaceHighest, 28 * dp)
                setPadding((4 * dp).toInt(), 0, (16 * dp).toInt(), 0)
                addView(iconButton("back", getString(R.string.back), m3.onSurface) { finish() })
                addView(filter, LinearLayout.LayoutParams(0, -1, 1f))
            }, LinearLayout.LayoutParams(-1, (56 * dp).toInt()).apply { setMargins((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt()) })
            addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
            @Suppress("DEPRECATION")
            setOnApplyWindowInsetsListener { v, i -> v.setPadding(i.systemWindowInsetLeft, i.systemWindowInsetTop, i.systemWindowInsetRight, i.systemWindowInsetBottom); i }
        }
        root = android.widget.FrameLayout(this).apply { addView(panel) }
        setContentView(root)
    }

    override fun onDestroy() {
        loader.shutdownNow()
        if (isFinishing && onPicked === picked) onPicked = null
        super.onDestroy()
    }

    companion object {
        /** The waiting pick, handed over in memory: a result can't cross to another task. */
        var onPicked: ((String) -> Unit)? = null
    }
}
