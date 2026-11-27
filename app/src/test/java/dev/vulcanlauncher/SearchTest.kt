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
        // A number too long for an Int (a pasted phone number) is no timer, not a crash.
        assertEquals(null, command("timer 3000000000"))
        // A leading minus binds looser than a power.
        assertEquals(-4.0, calc("-2^2")!!, 1e-9)
        assertEquals(-18.0, calc("2*-3^2")!!, 1e-9)
        assertEquals(4.0, calc("(-2)^2")!!, 1e-9)
        assertEquals(0.125, calc("2^-3")!!, 1e-9)
        assertEquals(null, command("timer 99999999999999999999999"))
        assertEquals(null, command("timer 2000000 h"))
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

    @Test
    fun conversions() {
        assertEquals("5 km = 3.107 mi", convert("5 km in mi"))
        assertEquals("70 f = 21.11 c", convert("70 F to C"))
        assertEquals("100 c = 212 fahrenheit", convert("100 c in fahrenheit"))
        assertEquals("0 k = -273.2 c", convert("0 k in c"))
        assertEquals("2 cups = 473.2 ml", convert("2 cups in ml"))
        assertEquals("1 lb = 453.6 g", convert("1 lb to g"))
        assertEquals("100 kmh = 62.14 mph", convert("100 kmh in mph"))
        assertNull(convert("5 km in kg")) // not the same kind
        assertNull(convert("5 km in km"))
        assertNull(convert("km in mi"))
        assertNull(convert("timer 5m"))
    }

    @Test
    fun numbersAndLinks() {
        assertEquals("+91 98765 43210", phoneNumber("+91 98765 43210"))
        assertEquals("(555) 123-4567", phoneNumber("(555) 123-4567"))
        assertNull(phoneNumber("123456")) // too short: a search for an app with a number in its name
        assertNull(phoneNumber("12*3"))
        assertEquals("https://example.com", webLink("example.com"))
        assertEquals("http://x.org/a?b=1", webLink("http://x.org/a?b=1"))
        assertEquals("https://news.bbc.co.uk", webLink("news.bbc.co.uk"))
        assertNull(webLink("12.5"))
        assertNull(webLink("google maps"))
        assertNull(webLink("wifi"))
    }
}
