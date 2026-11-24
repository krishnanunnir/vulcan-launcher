package dev.vulcanlauncher

import java.io.DataInputStream
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Scores [legacyFill] and [plateTint] against how Nova 8.9.4 reshaped a corpus of F-Droid icons (#92), when the
 * corpus is in `.local/icon-corpus` (never committed). Prints the score and each icon it gets wrong.
 */
class IconCorpusTest {
    @Test
    fun matchesNova() {
        assumeTrue(File("../.local/icon-corpus/nova-labels.tsv").exists())
        val (right, wrong) = score()
        println("ring colour as Nova: $right/${right + wrong.size}")
        wrong.forEach(::println)
    }

    private fun score(): Pair<Int, List<String>> {
        val dir = File("../.local/icon-corpus")
        val n = 64
        var right = 0; var total = 0
        val wrong = mutableListOf<String>()
        for (line in File(dir, "nova-labels.tsv").readLines().filter { it.isNotBlank() }) {
            val (idx, kind, rgb) = line.split("\t")
            // Each icon scaled to 64×64 ARGB ints, big-endian, by the corpus's own script.
            val px = DataInputStream(File(dir, "px64/i$idx.argb").inputStream().buffered()).use { s -> IntArray(n * n) { s.readInt() } }
            val fill = legacyFill(px, n)
            // What shows round the icon: the fill's colour or the plate's, against Nova's at the same place.
            val got = (fill?.color ?: plateTint(px, n)).let { c -> listOf(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF) }
            val want = rgb.split(",").map { it.trim().toInt() }
            total++
            if (want.zip(got).sumOf { (a, b) -> Math.abs(a - b) } <= 60) right++
            else wrong += "$idx nova $kind $want, ours ${if (fill == null) "plate" else "fill"} $got"
        }
        return right to wrong
    }
}
