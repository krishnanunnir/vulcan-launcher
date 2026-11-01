package dev.vulcanlauncher

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import java.io.File

/** Checks both contributor portraits and the actual PNG handoff, without sending to a recipient. */
internal class PersonaCheck(private val inst: Instrumentation) {
    fun run() {
        val log = StringBuilder()
        fun expect(name: String, ok: Boolean) { check(ok) { name }; log.append("PASS $name\n") }
        try {
            inst.targetContext.startActivity(Intent(inst.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            var tries = 0
            while (MainActivity.running?.get()?.let { !it.isDestroyed && it.hasWindowFocus() } != true && tries++ < 100) SystemClock.sleep(100)
            val settings = inst.startActivitySync(Intent(inst.targetContext, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
            lateinit var profile: PersonaProfile
            for ((index, persona) in CONTRIBUTORS.withIndex()) {
                inst.runOnMainSync {
                    profile = settings.showPersona(persona)
                    val bitmap = Bitmap.createBitmap(1080, 1350, Bitmap.Config.ARGB_8888)
                    profile.card.render(Canvas(bitmap), 1080f, 1350f)
                    File(settings.filesDir, "persona-$index.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    val uri = PersonaCardProvider.save(settings, bitmap); bitmap.recycle()
                    settings.contentResolver.openInputStream(uri).use { input ->
                        val image = BitmapFactory.decodeStream(input)
                        expect("${persona.name} card is 1080 by 1350", image.width == 1080 && image.height == 1350); image.recycle()
                    }
                    expect("${persona.name} PNG MIME type", settings.contentResolver.getType(uri) == "image/png")
                    expect("card provider is read only", runCatching { settings.contentResolver.openFileDescriptor(uri, "w")?.close() }.isFailure)
                }
                SystemClock.sleep(350)
                if (index < CONTRIBUTORS.lastIndex) inst.runOnMainSync {
                    fun close(v: View) {
                        if (v is Button && v.text == settings.getString(R.string.persona_close)) v.performClick()
                        else if (v is ViewGroup) for (i in 0 until v.childCount) close(v.getChildAt(i))
                    }
                    close(profile)
                }
            }
            var chooser: Intent? = null
            val monitor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.action == Intent.ACTION_CHOOSER) chooser = intent
                    return null
                }
            }
            inst.addMonitor(monitor)
            inst.runOnMainSync {
                fun share(v: View): Boolean {
                    if (v is Button && v.text == settings.getString(R.string.persona_share)) return v.performClick()
                    if (v is ViewGroup) for (i in 0 until v.childCount) if (share(v.getChildAt(i))) return true
                    return false
                }
                expect("Share persona control exists", share(profile))
            }
            expect("persona opens Android share sheet", chooser != null)
            @Suppress("DEPRECATION") val send = chooser!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            expect("image attachment has temporary read access", send.type == "image/png" && send.clipData?.itemCount == 1 && send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            inst.removeMonitor(monitor)
            inst.finish(Activity.RESULT_OK, Bundle().apply { putString("stream", log.toString()) })
        } catch (e: Throwable) {
            inst.finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "$log\nFAIL ${e.stackTraceToString()}") })
        }
    }
}
