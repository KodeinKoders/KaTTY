package app.katty

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemFontsTest {
    private val families = SystemFonts.monospacedFamilies()

    @Test
    fun listsTheMonospacedFamiliesOfTheSystem() {
        // Every Mac has at least one of these.
        assertTrue(families.any { it in setOf("Menlo", "Monaco", "Courier New", "Courier") }, "Families: $families")
    }

    @Test
    fun doesNotListAProportionalFamily() {
        assertFalse("Helvetica" in families)
        assertFalse("Times New Roman" in families)
        assertFalse("Arial" in families)
    }

    @Test
    fun theFamiliesAreSortedAndHaveNoDuplicate() {
        assertTrue(families == families.sortedWith(String.CASE_INSENSITIVE_ORDER))
        assertTrue(families.size == families.toSet().size)
    }

    @Test
    fun theFamiliesTheTerminalCanDrawWith() {
        // The terminal refuses a font that is not monospaced: the ones that are listed are accepted.
        val accepted = families.take(5).count { family -> runCatching { app.katty.terminal.compose.TerminalFont.system(family) }.getOrNull() != null }
        assertTrue(accepted >= 1)
    }
}
