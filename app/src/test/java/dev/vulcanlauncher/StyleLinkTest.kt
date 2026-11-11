package dev.vulcanlauncher

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StyleLinkTest {
    private val prefix = "https://vulcanlauncher.dev/design/#s="

    private fun link(json: String) = prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    @Test
    fun readsDesignerSettingsWithoutImportingPrivateOrInvalidValues() {
        val url = link("""{"accent":"#abcdef","labels":false,"cols":6,"shape":"invalid","lat":"12.34","g_swipeDown":"app:example/Main/0"}""")
        assertEquals(mapOf("accent" to "#abcdef", "labels" to "off", "cols" to "6"), readStyleLink("  $url\n"))
        assertEquals(mapOf("labels" to "on"), readStyleLink(link("""{"labels":"on"}""")))
    }

    @Test
    fun exportedLinkUsesDesignerFormatAndOmitsDefaultsAndAppTargets() {
        val url = styleLink { choice ->
            when (choice.key) {
                "accent" -> "#4287F5"
                "g_swipeDown" -> "app:example/Main/0"
                else -> choice.default
            }
        }
        val data = JSONObject(Base64.getUrlDecoder().decode(url.removePrefix(prefix)).decodeToString())
        assertEquals(setOf("accent"), data.keys().asSequence().toSet())
        assertEquals("#4287F5", data.getString("accent"))
        assertEquals(emptyMap<String, String>(), readStyleLink(styleLink { it.default }))
    }

    @Test
    fun rejectsWrongSitesAndMalformedPayloads() {
        val valid = link("""{"labels":"off"}""")
        for (url in listOf(valid.replace(".dev/", ".dev.example/"), valid.replace("/design/", "/other/"),
            prefix + "?", link("[]"), link("{"), link("{}[]"), prefix + "a".repeat(8193))) {
            assertNull(url.take(100), readStyleLink(url))
        }
    }
}
