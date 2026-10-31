package dev.vulcanlauncher

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The Now page: feed cards that work offline (date, media controls, calendar, contacts, frequent apps).
// Weather, news and Google Discover need the network and aren't here.

fun MainActivity.granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

/** The locale's own pattern for a date skeleton ("EEEEdMMMM" is "Monday, 19 October" in English, "Montag, 19. Oktober" in German). */
private fun datePattern(skeleton: String) = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)

@SuppressLint("SetTextI18n", "ClickableViewAccessibility")
fun MainActivity.showNow() {
    drawer.hide()
    val pad = (16 * dp).toInt()
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, insets.top + pad, pad, insets.bottom + pad) }
    // M3 filled cards on a translucent surface.
    fun card(title: String, body: View) = list.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(m3.surfaceContainer, 24 * dp)
        setPadding(pad + pad / 4, pad, pad + pad / 4, pad + pad / 4)
        addView(text(title, 14f, m3.primary, medium = true).apply { setPadding(0, 0, 0, pad * 3 / 4) })
        addView(body)
    }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = pad * 3 / 4 })
    fun text(s: CharSequence, size: Float = 15f) = text(s, size, m3.onSurface)
    fun action(label: String, onClick: () -> Unit) = LinearLayout(this).apply { addView(tonalButton(label, onClick)) }
    fun ask(permission: String, why: String) = action(why) { requestPermissions(arrayOf(permission), MainActivity.REQ_PERMISSION) }

    if (looks.on("nowClock")) list.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad / 2, pad, 0, pad * 3 / 2)
        addView(TextClock(context).apply {
            format12Hour = "h:mm"; format24Hour = "HH:mm"; textSize = 57f; setTextColor(m3.onSurface)
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        })
        addView(TextClock(context).apply { format12Hour = datePattern("EEEEdMMMM"); format24Hour = format12Hour; textSize = 16f; setTextColor(m3.onSurfaceVariant) })
    })

    if (looks.on("nowMedia")) {
        val sessions = runCatching {
            getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(this, BadgeService::class.java))
        }
        val c = sessions.getOrNull()?.firstOrNull()
        card(getString(R.string.now_media), when {
            sessions.isFailure -> action(getString(R.string.now_notification_access)) {
                startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            c == null -> text(getString(R.string.now_nothing_playing))
            else -> LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                val md = c.metadata
                md?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { art ->
                    addView(ImageView(context).apply {
                        setImageBitmap(art); scaleType = ImageView.ScaleType.CENTER_CROP
                        background = rounded(m3.surfaceHigh, 12 * dp); clipToOutline = true
                    },
                        LinearLayout.LayoutParams((56 * dp).toInt(), (56 * dp).toInt()).apply { marginEnd = pad })
                }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: getString(R.string.now_playing), 16f).apply { maxLines = 1 })
                    addView(text(md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: "", 14f).apply { maxLines = 1; setTextColor(m3.onSurfaceVariant) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
                addView(iconButton("prev", getString(R.string.media_previous), m3.onSurfaceVariant) { c.transportControls.skipToPrevious() })
                // The play/pause button is the card's filled primary action.
                addView(ImageView(context).apply {
                    setImageDrawable(symbol(if (playing) "pause" else "play", m3.onPrimary))
                    contentDescription = getString(if (playing) R.string.media_pause else R.string.media_play)
                    scaleType = ImageView.ScaleType.CENTER
                    background = pressable(rounded(m3.primary, 24 * dp), 24 * dp, m3.onPrimary)
                    setOnClickListener { if (playing) c.transportControls.pause() else c.transportControls.play(); closeOverlay(); showNow() }
                }, LinearLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt()))
                addView(iconButton("next", getString(R.string.media_next), m3.onSurfaceVariant) { c.transportControls.skipToNext() })
                setOnClickListener { c.sessionActivity?.let { runCatching { it.send() } } }
            }
        })
    }

    if (looks.on("nowCalendar")) card(getString(R.string.now_upcoming), if (!granted(Manifest.permission.READ_CALENDAR)) ask(Manifest.permission.READ_CALENDAR, getString(R.string.now_allow_calendar))
    else LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now); ContentUris.appendId(it, now + 7 * 86_400_000L)
        }.build()
        val fmt = SimpleDateFormat(datePattern("EEEjmm"), Locale.getDefault())
        // All-day events are stored as UTC midnights, so they're compared and shown in UTC.
        val dayFmt = SimpleDateFormat("EEE", Locale.getDefault()).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val todayUtc = java.util.Calendar.getInstance().let { l ->
            java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                clear(); set(l.get(java.util.Calendar.YEAR), l.get(java.util.Calendar.MONTH), l.get(java.util.Calendar.DAY_OF_MONTH))
            }.timeInMillis
        }
        runCatching {
            contentResolver.query(uri, arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY),
                null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext() && childCount < 5) {
                    val id = c.getLong(0)
                    val allDay = c.getInt(4) == 1
                    if (allDay && c.getLong(3) <= todayUtc) continue // ended before today
                    val whenText = if (allDay) getString(R.string.now_all_day, dayFmt.format(Date(c.getLong(2)))) else fmt.format(Date(c.getLong(2)))
                    addView(LinearLayout(context).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, pad / 3, 0, pad / 3)
                        background = pressable(null, 8 * dp)
                        addView(View(context).apply { background = rounded(m3.primary, 2 * dp) }, LinearLayout.LayoutParams((4 * dp).toInt(), (36 * dp).toInt()).apply { marginEnd = pad * 3 / 4 })
                        addView(LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(text(c.getString(1) ?: "", 16f).apply { maxLines = 1 })
                            addView(text(whenText, 14f).apply { setTextColor(m3.onSurfaceVariant) })
                        })
                        setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id))) } }
                    })
                }
            }
        }
        if (childCount == 0) addView(text(getString(R.string.now_no_events), 14f).apply { setTextColor(m3.onSurfaceVariant) })
    })

    if (looks.on("nowContacts")) card(getString(R.string.now_favourites), if (!granted(Manifest.permission.READ_CONTACTS)) ask(Manifest.permission.READ_CONTACTS, getString(R.string.now_allow_contacts))
    else HorizontalScrollView(this).apply {
        val row = LinearLayout(context)
        addView(row)
        runCatching {
            contentResolver.query(ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI),
                "${ContactsContract.Contacts.STARRED}=1", null, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)?.use { c ->
                while (c.moveToNext() && row.childCount < 12) {
                    val look = ContactsContract.Contacts.getLookupUri(c.getLong(0), c.getString(1))
                    row.addView(contactView(c.getString(2) ?: "", c.getString(3)) { startActivity(Intent(Intent.ACTION_VIEW, look)) })
                }
            }
        }
        if (row.childCount == 0) row.addView(text(getString(R.string.now_star_contacts), 14f).apply { setTextColor(m3.onSurfaceVariant) })
    })

    if (looks.on("nowApps")) {
        val counts = launches()
        val top = apps.values.filter { counts.optInt(it.key) > 0 }.sortedByDescending { counts.optInt(it.key) }.take(8)
        if (top.isNotEmpty()) card(getString(R.string.now_frequent), LinearLayout(this).apply {
            val st = style("").let { IconStyle(it.sizePx * 3 / 4, false, it.labelSize, panelText, false, false) }
            top.forEach { app -> addView(IconView(context, label(app), icon(app), st, badgeStyle(), app.pkg).apply { setOnClickListener { v -> launch(app, v) } },
                LinearLayout.LayoutParams(0, (st.sizePx + 16 * dp).toInt(), 1f)) }
        })
    }

    val panel = NowPanel(this).apply {
        setBackgroundColor(m3.surface and 0xFFFFFF or 0xE0000000.toInt())
        isClickable = true
        addView(ScrollView(context).apply { addView(list) })
    }
    showOverlay(panel)
    // Slides in from the left, the way the swipe right that opened it points.
    panel.translationX = -root.width.toFloat()
    panel.animate().translationX(0f).setDuration(260).setInterpolator(EASE).start()
}

/**
 * The Now page's frame: a leftward swipe anywhere (even over the scrolling cards) drags the page off to the left,
 * home sharpening behind it; let go past a third of the width, or fling, and it closes, otherwise it springs back.
 */
@SuppressLint("ViewConstructor")
private class NowPanel(private val a: MainActivity) : FrameLayout(a) {
    private val slop = android.view.ViewConfiguration.get(a).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var velocity: android.view.VelocityTracker? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY }
            MotionEvent.ACTION_MOVE -> {
                val dx = downX - ev.rawX
                if (dx > slop && dx > 2 * kotlin.math.abs(ev.rawY - downY)) {
                    downX = ev.rawX // follow from here, without jumping the slop
                    velocity = android.view.VelocityTracker.obtain()
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val v = velocity ?: return super.onTouchEvent(ev)
        v.addMovement(ev)
        val x = minOf(0f, ev.rawX - downX)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> { translationX = x; a.setDepth(1 + x / width) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.computeCurrentVelocity(1000)
                val fling = 1000 * a.dp
                val close = ev.actionMasked == MotionEvent.ACTION_UP && (v.xVelocity < -fling || v.xVelocity < fling && -x > width / 3f)
                v.recycle(); velocity = null
                animate().translationX(if (close) -width.toFloat() else 0f).setDuration(200).setInterpolator(EASE)
                    .setUpdateListener { a.setDepth(1 + translationX / width) }
                    .withEndAction { if (close) a.closeOverlay() }.start()
            }
        }
        return true
    }
}

private fun MainActivity.contactView(name: String, photo: String?, onClick: () -> Unit) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER_HORIZONTAL
    val s = (52 * dp).toInt()
    setPadding((6 * dp).toInt(), 0, (6 * dp).toInt(), 0)
    addView(if (photo != null) ImageView(context).apply {
        setImageURI(Uri.parse(photo))
        clipToOutline = true
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(m3.primaryContainer) }
    } else TextView(context).apply {
        text = name.take(1).uppercase()
        textSize = 22f
        gravity = Gravity.CENTER
        setTextColor(m3.onPrimaryContainer)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(m3.primaryContainer) }
    }, LinearLayout.LayoutParams(s, s))
    addView(text(name.substringBefore(" "), 12f, m3.onSurfaceVariant).apply { maxLines = 1; setPadding(0, (6 * dp).toInt(), 0, 0) })
    setOnClickListener { onClick() }
}

// ---- Clock: a home item showing the time, date and one line of status ----

/**
 * The clock item's view. Appliance: a large light clock over a printed legend (date · status); otherwise the date over
 * a status line ("glanceStyle" picks either). The big clock grows with the item, like a clock widget. The date or
 * clock opens the calendar or clock; the status line is filled by [updateGlance]. A long-press anywhere on it is the
 * item's own (move, resize, remove).
 */
fun MainActivity.clockView() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.BOTTOM
    val big = when (looks.str("glanceStyle")) { "clock" -> true; "date" -> false; else -> m3.housing }
    tag = big
    val box = this
    val hold = View.OnLongClickListener { box.performLongClick() }
    setPadding((16 * dp).toInt(), (4 * dp).toInt(), (16 * dp).toInt(), (4 * dp).toInt())
    val color = looks.color("labelColor")
    fun TextView.look() = apply { if (looks.on("labelShadow")) setShadowLayer(4 * dp, 0f, dp, 0x66000000) }
    val calendar = { runCatching { startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; Unit }
    if (big) {
        addView(TextClock(context).apply {
            format12Hour = "h:mm"; format24Hour = "HH:mm"
            setAutoSizeTextTypeUniformWithConfiguration(20, 200, 2, android.util.TypedValue.COMPLEX_UNIT_SP)
            gravity = Gravity.START or Gravity.BOTTOM
            maxLines = 1
            letterSpacing = -0.02f
            includeFontPadding = false
            setTextColor(color)
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
            look()
            setOnClickListener { runCatching { startActivity(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            setOnLongClickListener(hold)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
    }
    // Appliance: date and status on one printed legend line ("MON 19 OCT · CHARGED"); otherwise two lines.
    val legend = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    legend.addView(TextClock(context).apply {
        if (big) {
            format12Hour = datePattern("EEEdMMM"); format24Hour = format12Hour
            textSize = 13f
            letterSpacing = 0.12f
            isAllCaps = true
            setPadding(0, (6 * dp).toInt(), 0, 0)
        } else {
            format12Hour = datePattern("EEEEdMMMM"); format24Hour = format12Hour
            textSize = 22f
        }
        maxLines = 1
        setTextColor(color)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        look()
        setOnClickListener { calendar() }
        setOnLongClickListener(hold)
    }, LinearLayout.LayoutParams(-2, -2))
    if (big) addView(legend, LinearLayout.LayoutParams(-2, -2)) else addView(legend.getChildAt(0).also { legend.removeAllViews() })
    (if (big) legend else this).addView(TextView(context).apply {
        tag = "status"
        textSize = if (big) 13f else 15f
        if (big) {
            letterSpacing = 0.12f; isAllCaps = true
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(0, (6 * dp).toInt(), 0, 0)
        } else setPadding(0, (2 * dp).toInt(), 0, 0)
        setTextColor(if (big) color else color and 0xFFFFFF or 0xD9000000.toInt())
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        look()
        setOnLongClickListener(hold)
    }, LinearLayout.LayoutParams(-2, -2))
}

/** The clock views on home, from [MainActivity.views]. */
val MainActivity.clocks get() = views.filterKeys { it.isClock }.values

/** Refreshes the glance status line: what's playing, else the next event today, else charging, else nothing. */
fun MainActivity.updateGlance() {
    if (clocks.isEmpty()) return
    val (text, tap) = glanceStatus() ?: (null to null)
    for (c in clocks) {
        val line = c.findViewWithTag<TextView>("status") ?: continue
        line.text = if (text != null && c.tag == true) " · $text" else text
        line.visibility = if (text == null) View.GONE else View.VISIBLE
        line.setOnClickListener(tap?.let { f -> View.OnClickListener { f() } })
    }
}

private fun MainActivity.glanceStatus(): Pair<String, (() -> Unit)?>? {
    // Media sessions are readable only with notification access (the badge service).
    runCatching {
        getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(this, BadgeService::class.java))
            .firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
    }.getOrNull()?.let { c ->
        val md = c.metadata
        val what = listOfNotNull(md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE), md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST))
            .filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { getString(R.string.now_playing) }
        return "♪  $what" to { c.sessionActivity?.let { runCatching { it.send() } }; Unit }
    }
    if (granted(Manifest.permission.READ_CALENDAR)) runCatching {
        val now = System.currentTimeMillis()
        val endOfDay = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 23); set(java.util.Calendar.MINUTE, 59); set(java.util.Calendar.SECOND, 59)
        }.timeInMillis
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, now); ContentUris.appendId(it, endOfDay) }.build()
        contentResolver.query(uri, arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN),
            "${CalendarContract.Instances.ALL_DAY}=0 AND ${CalendarContract.Instances.END}>?", arrayOf(now.toString()),
            "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                val at = if (c.getLong(2) <= now) getString(R.string.now_now) else android.text.format.DateFormat.getTimeFormat(this).format(Date(c.getLong(2)))
                return "$at  ${c.getString(1) ?: ""}" to {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    Unit
                }
            }
        }
    }
    val b = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
    val level = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) * 100 / maxOf(1, b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100))
    return when (b.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)) {
        android.os.BatteryManager.BATTERY_STATUS_CHARGING -> getString(R.string.charging, level) to null
        android.os.BatteryManager.BATTERY_STATUS_FULL -> getString(R.string.charged) to null
        else -> null
    }
}
