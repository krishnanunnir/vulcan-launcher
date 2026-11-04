package dev.vulcanlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    @Test
    fun matching() {
        assertEquals(5, matchScore("Google Maps", "goo"))
        assertEquals(4, matchScore("Google Maps", "map"))
        assertEquals(3, matchScore("Google Maps", "ogl"))
        assertEquals(2, matchScore("Google Maps", "gm"))
        assertEquals(1, matchScore("WhatsApp", "whtsapp")) // a letter missed
        assertEquals(1, matchScore("WhatsApp", "wahtsapp")) // two letters swapped
        assertEquals(1, matchScore("Spotify", "spotfy"))
        assertEquals(5, matchScore("Wi-Fi", "wifi"))
        assertEquals(5, matchScore("Café", "cafe"))
        assertEquals(0, matchScore("Camera", "xyzw"))
        assertEquals(0, matchScore("Camera", "cmr")) // too short to guess at
        assertTrue(matchScore("Calendar", "cal") > matchScore("Local", "cal"))
        assertEquals(1, editDistance("ab", "ba"))
        assertEquals(3, editDistance("", "abc"))
    }

    @Test
    fun calculator() {
        assertEquals(7.0, calc("1+2*3")!!, 0.0)
        assertEquals(9.0, calc("(1+2)*3")!!, 0.0)
        assertEquals(512.0, calc("2^3^2")!!, 0.0)
        assertEquals(1.5, calc("3 ÷ 2")!!, 0.0)
        assertEquals(-4.0, calc("-2*2")!!, 0.0)
        assertEquals(30.0, calc("50%*60")!!, 1e-9)
        assertEquals(1.0, calc("7%3")!!, 0.0)
        assertEquals(2.5, calc("1,5+1")!!, 0.0)
        assertNull(calc("42")) // a number alone isn't a sum
        assertNull(calc("2048 game"))
        assertNull(calc("1/0"))
        assertNull(calc("1+"))
        assertNull(calc("+"))
        assertEquals("0.3333333333", calcText(1 / 3.0))
        assertEquals("12", calcText(12.0))
        assertEquals("0.3", calcText(0.1 + 0.2))
    }

    @Test
    fun commands() {
        assertEquals(Command.Timer(300), command("timer 5m"))
        assertEquals(Command.Timer(300), command("timer 5"))
        assertEquals(Command.Timer(5400), command("Timer 1h 30m"))
        assertEquals(Command.Timer(90), command("timer 90 sec"))
        assertEquals(Command.Alarm(7, 0), command("alarm 7"))
        assertEquals(Command.Alarm(19, 30), command("alarm 7:30 pm"))
        assertEquals(Command.Alarm(0, 15), command("alarm 12.15am"))
        assertEquals(Command.Alarm(19, 30), command("alarm 19:30"))
        assertNull(command("alarm 25"))
        assertNull(command("alarm 13pm"))
        assertNull(command("timer soon"))
        assertNull(command("timer 0"))
        assertNull(command("alarms"))
    }
}
