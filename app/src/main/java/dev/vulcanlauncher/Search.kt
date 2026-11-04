package dev.vulcanlauncher

import java.text.Normalizer
import kotlin.math.pow

// ---- Drawer search: matching, the calculator and quick commands. Pure logic, tested in SearchTest. ----

/** Lowercase, without accents or punctuation: "Wi-Fi" and "wifi", "Café" and "cafe" are the same word. */
fun searchForm(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "").replace(Regex("[^\\p{L}\\p{N}\\s]"), "").trim()

/**
 * How well [label] matches the query [q], higher is better, 0 none: the label starts with it (5), a word does (4),
 * it's inside (3), the words' initials start with it ("gm", Google Maps: 2), or a word starts with it but for one slip
 * of the finger (two in a query of 7 or more letters: 1).
 */
fun matchScore(label: String, q: String): Int {
    val l = searchForm(label); val s = searchForm(q)
    if (s.isEmpty()) return 0
    val words = l.split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        l.startsWith(s) -> 5
        words.any { it.startsWith(s) } -> 4
        s in l -> 3
        s.length >= 2 && words.joinToString("") { it.take(1) }.startsWith(s) -> 2
        s.length >= 4 && words.any { w -> nearPrefix(w, s, if (s.length >= 7) 2 else 1) } -> 1
        else -> 0
    }
}

/** Whether some prefix of [word] is within [max] edits (a swap of two letters is one) of [s]. */
private fun nearPrefix(word: String, s: String, max: Int): Boolean =
    (s.length - max..s.length + max).any { n -> n in 1..word.length && editDistance(word.substring(0, n), s) <= max }

/** Optimal string alignment distance: insertions, deletions, substitutions and adjacent swaps. */
fun editDistance(a: String, b: String): Int {
    val d = Array(a.length + 1) { i -> IntArray(b.length + 1) { j -> if (i == 0) j else if (j == 0) i else 0 } }
    for (i in 1..a.length) for (j in 1..b.length) {
        val cost = if (a[i - 1] == b[j - 1]) 0 else 1
        d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
        if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
    }
    return d[a.length][b.length]
}

/**
 * The value of [q] when it's a sum: numbers, + − × ÷ ^ %, and brackets. Null for anything else, a bare number
 * included (that's a search for an app with a number in its name).
 */
fun calc(q: String): Double? {
    val s = q.replace(" ", "").replace('×', '*').replace('÷', '/').replace('−', '-').replace(',', '.')
    if (s.isEmpty() || s.any { it !in "0123456789.+-*/^%()" } || s.none { it in "+-*/^%" } || s.none { it.isDigit() }) return null
    var i = 0
    fun peek() = s.getOrNull(i)
    lateinit var sum: () -> Double
    fun atom(): Double {
        if (peek() == '-') { i++; return -atom() }
        if (peek() == '(') { i++; val v = sum(); if (peek() == ')') i++; return v }
        val start = i
        while (peek()?.let { it.isDigit() || it == '.' } == true) i++
        val v = s.substring(start, i).toDouble()
        // A trailing % is a percentage: 50% is 0.5.
        return if (peek() == '%' && s.getOrNull(i + 1)?.let { it.isDigit() || it == '(' } != true) { i++; v / 100 } else v
    }
    fun power(): Double { val b = atom(); return if (peek() == '^') { i++; b.pow(power()) } else b }
    fun product(): Double {
        var v = power()
        while (true) when (peek()) {
            '*' -> { i++; v *= power() }
            '/' -> { i++; v /= power() }
            '%' -> { i++; v %= power() }
            else -> return v
        }
    }
    sum = {
        var v = product()
        while (peek() == '+' || peek() == '-') { if (s[i++] == '+') v += product() else v -= product() }
        v
    }
    return runCatching { sum().takeIf { i == s.length && it.isFinite() } }.getOrNull()
}

/** [v] as a calculator shows it: up to 10 significant digits, no trailing zeros. */
fun calcText(v: Double): String =
    java.math.BigDecimal(v).round(java.math.MathContext(10)).stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }

/** A timer of [seconds], or an alarm at [hour]:[minute]. */
sealed class Command {
    data class Timer(val seconds: Int) : Command()
    data class Alarm(val hour: Int, val minute: Int) : Command()
}

/**
 * "timer 5m", "timer 1h 30m", "timer 90 sec"; "alarm 7", "alarm 7:30 pm", "alarm 19.30". Null for anything else.
 * ponytail: English keywords only; add translated ones to strings.xml if non-English users ask.
 */
fun command(q: String): Command? {
    val s = q.trim().lowercase()
    Regex("^timer\\s+(.+)$").find(s)?.let { m ->
        val parts = Regex("(\\d+)\\s*(hours?|hrs|hr|h|minutes?|mins|min|m|seconds?|secs|sec|s)?\\s*").findAll(m.groupValues[1]).toList()
        if (parts.isEmpty() || parts.joinToString("") { it.value } != m.groupValues[1]) return null
        val secs = parts.sumOf { p ->
            val n = p.groupValues[1].toInt()
            when (p.groupValues[2].firstOrNull()) { 'h' -> n * 3600; 's' -> n; else -> n * 60 } // a bare number is minutes
        }
        return if (secs in 1..86_399) Command.Timer(secs) else null
    }
    Regex("^alarm\\s+(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?$").find(s)?.let { m ->
        var h = m.groupValues[1].toInt(); val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val half = m.groupValues[3].firstOrNull()
        if (half != null) { if (h !in 1..12) return null; h = h % 12 + if (half == 'p') 12 else 0 }
        return if (h in 0..23 && min in 0..59) Command.Alarm(h, min) else null
    }
    return null
}
