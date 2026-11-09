package app.katty.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistorySearchTest {
    private val lines = listOf("$ make build", "error: Build failed", "", "ok", "build again: BUILD build")

    @Test
    fun theLastLinesComeFirstWithTheirRows() {
        val found = matchLines(lines, firstRow = 100, query = "build", limit = 10)

        assertEquals(listOf(104L, 101L, 100L), found.map { it.row })
        assertEquals("error: Build failed", found[1].line)
    }

    @Test
    fun everyTimeTheTextIsInALineIsOneRange() {
        assertEquals(listOf(0..4, 13..17, 19..23), matchLines(lines, 0, "build", 10).first().ranges)
        assertEquals(listOf(0..1, 2..3), matchLines(listOf("aaaa"), 0, "aa", 10).single().ranges, "Without overlapping")
    }

    @Test
    fun onlyTheCaseOfAsciiLettersIsIgnored() {
        assertEquals(1, matchLines(listOf("Café"), 0, "CAF", 10).size)
        assertTrue(matchLines(listOf("CAFÉ"), 0, "café", 10).isEmpty(), "É is not é for libghostty's search either")
        assertEquals(listOf(0..3), matchLines(listOf("CAFÉ"), 0, "cafÉ", 10).single().ranges)
    }

    @Test
    fun itStopsAtTheLimitAndAnEmptyQueryFindsNothing() {
        assertEquals(listOf(4L, 1L), matchLines(lines, 0, "build", 2).map { it.row })
        assertTrue(matchLines(lines, 0, "", 10).isEmpty())
        assertTrue(matchLines(lines, 0, "nowhere", 10).isEmpty())
    }
}
