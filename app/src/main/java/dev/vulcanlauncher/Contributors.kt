package dev.vulcanlauncher

import android.app.Dialog
import android.widget.LinearLayout

/** What makes each contributor's illustrated persona recognisable. */
enum class Hat { HARD_HAT, SPEECH_BUBBLE, MAGNIFIER, RULER }
class Persona(val name: String, val role: Int, val title: Int, val tagline: Int, val signature: Int, val hat: Hat)

/** Credit code, translations, testing and feedback with a persona that fits the contribution. */
val CONTRIBUTORS = listOf(
    Persona("Krishnanunni R", R.string.contrib_creator, R.string.persona_architect, R.string.persona_architect_line, R.string.persona_architect_signature, Hat.HARD_HAT),
    Persona("LiterBol", R.string.contrib_translations, R.string.persona_polyglot, R.string.persona_polyglot_line, R.string.persona_polyglot_signature, Hat.SPEECH_BUBBLE),
    // Android 11 device testing, crash traces and follow-up verification in fdroiddata!54678.
    Persona("HatsyRei", R.string.contrib_testing, R.string.persona_bug_hunter, R.string.persona_bug_hunter_line, R.string.persona_bug_hunter_signature, Hat.MAGNIFIER),
    // Usability and feature feedback across GitHub issues, including #20, #24, #26 and #33.
    Persona("SadieVane", R.string.contrib_feedback, R.string.persona_refiner, R.string.persona_refiner_line, R.string.persona_refiner_signature, Hat.RULER),
)
const val CONTRIBUTORS_PAGE = "Contributors"
fun initials(name: String) = name.split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }

fun SettingsActivity.contributorsPage(list: LinearLayout) {
    list.addView(text(getString(R.string.contrib_thanks), 14f, m3.onSurfaceVariant).apply {
        setPadding((32 * dp).toInt(), (8 * dp).toInt(), (32 * dp).toInt(), (8 * dp).toInt())
    })
    panel(list, null, CONTRIBUTORS.map { p ->
        listItem(symbol("person", m3.onPrimaryContainer), p.name, getString(p.role), tonalIcon = true) { showPersona(p) }
    })
    list.addView(text(getString(R.string.persona_hint), 13f, m3.onSurfaceVariant).apply {
        setPadding((32 * dp).toInt(), (24 * dp).toInt(), (32 * dp).toInt(), (12 * dp).toInt())
    })
}

internal fun SettingsActivity.showPersona(p: Persona): PersonaProfile {
    val dialog = Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
    val profile = PersonaProfile(this, p) { dialog.dismiss() }
    dialog.setContentView(profile)
    dialog.show()
    return profile
}
