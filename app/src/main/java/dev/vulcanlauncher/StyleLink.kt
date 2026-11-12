package dev.vulcanlauncher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.text.InputType
import android.widget.LinearLayout
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Base64

private const val DESIGNER = "https://vulcanlauncher.dev/design/"
private const val MAX_PAYLOAD = 8192
private val styleUrl = Regex("https://vulcanlauncher\\.dev/design/?#s=([A-Za-z0-9_-]+)")

/** A Designer #s link holds a UTF-8 JSON object encoded as unpadded URL-safe Base64. */
fun styleLink(value: (Choice) -> String): String {
    val settings = JSONObject()
    for (choice in LOOKS) {
        val v = value(choice)
        if (v != choice.default && choice.shareable(v)) settings.put(choice.key, v)
    }
    return DESIGNER + "#s=" + Base64.getUrlEncoder().withoutPadding().encodeToString(settings.toString().toByteArray())
}

/** Only portable, recognised settings can be shared or applied. App and shortcut actions carry a device's profile ID. */
private fun Choice.shareable(value: String) = !value.startsWith("app:") && !value.startsWith("shortcut:") && accepts(value)

/** Null means an unreadable link; an empty map is a readable link with no supported changes. Never fetches the URL. */
fun readStyleLink(text: String): Map<String, String>? {
    val url = text.trim()
    if (url.length > DESIGNER.length + 3 + MAX_PAYLOAD) return null
    val payload = styleUrl.matchEntire(url)?.groupValues?.get(1) ?: return null
    if (payload.length > MAX_PAYLOAD) return null
    val settings = runCatching {
        val bytes = Base64.getUrlDecoder().decode(payload)
        val parser = JSONTokener(bytes.decodeToString(throwOnInvalidSequence = true))
        (parser.nextValue() as? JSONObject)?.takeIf { parser.nextClean() == '\u0000' }
    }.getOrNull() ?: return null
    return buildMap {
        for (choice in LOOKS) {
            val value = when (val raw = settings.opt(choice.key)) {
                true -> "on"
                false -> "off"
                is String, is Number -> raw.toString()
                else -> continue
            }
            if (choice.shareable(value)) put(choice.key, value)
        }
    }
}

fun SettingsActivity.shareStyle() {
    val url = styleLink { looks[it] }
    dialog().setTitle(R.string.share_style_link).setMessage(R.string.share_style_message)
        .setPositiveButton(R.string.share_style_send) { _, _ ->
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
            startActivity(Intent.createChooser(send, getString(R.string.share_style_link)))
        }
        .setNeutralButton(R.string.share_style_copy) { _, _ ->
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(getString(R.string.share_style_link), url))
            if (Build.VERSION.SDK_INT < 33) toast(R.string.copied)
        }
        .setNegativeButton(R.string.cancel, null).show()
}

fun SettingsActivity.pasteStyle() {
    val pad = (24 * dp).toInt()
    val link = field("", getString(R.string.apply_style_hint)).apply {
        // field() is already single line; calling setSingleLine() again would reset its 56 dp height to one text line.
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    }
    val box = LinearLayout(this).apply {
        setPadding(pad, (8 * dp).toInt(), pad, 0)
        addView(link, LinearLayout.LayoutParams(-1, -2))
    }
    val picker = sheet().setTitle(R.string.apply_style_link).setView(box)
        .setPositiveButton(R.string.apply_style_review, null)
        .setNeutralButton(R.string.apply_style_paste, null)
        .setNegativeButton(R.string.cancel, null).show()
    picker.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
        if (text.isNullOrBlank()) link.error = getString(R.string.apply_style_empty_clipboard)
        else {
            link.setText(text)
            link.setSelection(link.length())
        }
    }
    picker.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val settings = readStyleLink(link.text.toString())
        if (settings == null) {
            link.error = getString(R.string.apply_style_invalid)
            return@setOnClickListener
        }
        picker.dismiss()
        val changes = settings.filter { (key, value) -> looks.str(key) != value }
        if (changes.isEmpty()) {
            toast(R.string.apply_style_no_changes)
            return@setOnClickListener
        }
        dialog().setTitle(R.string.apply_style_link)
            .setMessage(resources.getQuantityString(R.plurals.apply_style_confirm, changes.size, changes.size))
            .setPositiveButton(R.string.apply_style_apply) { _, _ ->
                looks.applyStyle(changes)
                changed()
                toast(R.string.apply_style_done)
            }
            .setNegativeButton(R.string.cancel, null).show()
    }
}
