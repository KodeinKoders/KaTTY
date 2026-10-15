package app.katty.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaletteTest {
    @Test
    fun theLettersOfTheQueryAreFoundInOrder() {
        assertEquals(listOf(0, 2), fuzzyMatch("rn", "Rename")!!.second)
        assertEquals(listOf(0, 1), fuzzyMatch("re", "refactor-auth")!!.second)
        assertNull(fuzzyMatch("nr", "re"))
        assertNull(fuzzyMatch("xyz", "Rename"))
    }

    @Test
    fun theCaseDoesNotMatter() {
        assertEquals(listOf(0, 1, 2), fuzzyMatch("REN", "rename")!!.second)
        assertEquals(listOf(0, 1, 2), fuzzyMatch("ren", "RENAME")!!.second)
    }

    @Test
    fun anEmptyQueryMatchesEverythingWithNothingMarked() {
        assertEquals(0 to emptyList(), fuzzyMatch("", "anything"))
        assertEquals(0 to emptyList(), fuzzyMatch("  ", "anything"))
    }

    @Test
    fun spacesInTheQueryAreNotLettersToFind() {
        assertEquals(listOf(0, 1, 6, 7), fuzzyMatch("sp ri", "split right")!!.second)
    }

    @Test
    fun lettersTogetherAtTheStartOfAWordBeatLettersScatteredAboutTheText() {
        val together = fuzzyMatch("split", "Split right")!!.first
        val scattered = fuzzyMatch("split", "Show the plan in a little table")!!.first

        assertTrue(together > scattered)
    }

    @Test
    fun theStartOfAWordBeatsTheMiddleOfOne() {
        val word = fuzzyMatch("re", "Restart terminal")!!.first
        val middle = fuzzyMatch("re", "Fire")!!.first

        assertTrue(word > middle)
    }

    @Test
    fun aShorterTextBeatsALongerOneWithTheSameMatch() {
        assertTrue(fuzzyMatch("rel", "release-notes")!!.first > fuzzyMatch("rel", "release-notes-for-the-whole-team")!!.first)
    }

    private val entries = listOf(
        PaletteEntry("w1", PaletteGroup.WORKSPACES, "refactor-auth", "api-gateway"),
        PaletteEntry("w2", PaletteGroup.WORKSPACES, "release-notes", "api-gateway"),
        PaletteEntry("t1", PaletteGroup.TERMINALS, "Claude Code", "refactor-auth · working"),
        PaletteEntry("c1", PaletteGroup.COMMANDS, "Restart terminal", shortcut = "⌘R"),
        PaletteEntry("c2", PaletteGroup.COMMANDS, "Rename workspace", shortcut = "F2"),
        PaletteEntry("c3", PaletteGroup.COMMANDS, "Split right", shortcut = "⌘D"),
    )

    @Test
    fun withNoQueryEveryEntryIsListedInTheOrderOfTheGroups() {
        val matches = searchPalette(entries, "")

        assertEquals(entries.map { it.id }, matches.map { it.entry.id })
        assertTrue(matches.all { it.indices.isEmpty() })
    }

    @Test
    fun theQueryKeepsTheEntriesThatMatchAndMarksTheirLetters() {
        val matches = searchPalette(entries, "re")

        // "Split right" has no e after its r.
        assertEquals(listOf("w1", "w2", "c1", "c2"), matches.map { it.entry.id }.sortedBy { id -> entries.indexOfFirst { it.id == id } })
        assertEquals(listOf(0, 1), matches.first { it.entry.id == "w1" }.indices)
    }

    @Test
    fun theGroupsStayInOrderAndTheBestOfAGroupComesFirst() {
        val matches = searchPalette(entries, "ren")

        val groups = matches.map { it.entry.group }
        assertEquals(groups.sortedBy { it.ordinal }, groups)
        // The command that is called Rename is better than the ones that only have its letters.
        assertEquals("c2", matches.first { it.entry.group == PaletteGroup.COMMANDS }.entry.id)
    }

    @Test
    fun aQueryThatMatchesNothingGivesNothing() {
        assertEquals(emptyList(), searchPalette(entries, "zzz"))
    }

    @Test
    fun theBestMatchOfAGroupIsFirst() {
        val list = listOf(
            PaletteEntry("a", PaletteGroup.COMMANDS, "Show the plan in a little table"),
            PaletteEntry("b", PaletteGroup.COMMANDS, "Split right"),
        )

        assertEquals(listOf("b", "a"), searchPalette(list, "split").map { it.entry.id })
    }
}
