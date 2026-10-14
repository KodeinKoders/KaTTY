package app.katty.terminal.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TextFallbackTest {
    @Test
    fun aSymbolThatCanBeAnEmojiButIsNotOneByDefaultWantsToBeText() {
        for (text in listOf("\u23F8", "\u23FA", "\u2764", "\u26A0", "\u2714", "\u25B6", "\u00A9")) {
            assertEquals(true, wantsTextPresentation(text), text)
        }
    }

    @Test
    fun aSymbolThatIsNotAnEmojiAtAllDoesNot() {
        // The play button of two of them, which only text fonts have, so that the system picks one.
        assertEquals(false, wantsTextPresentation("\u23F5"))
    }

    @Test
    fun anEmojiThatIsOneByDefaultDoesNot() {
        assertEquals(false, wantsTextPresentation("\uD83D\uDE00"))
        assertEquals(false, wantsTextPresentation("\u231A"))
    }

    @Test
    fun aSymbolThatIsAskedToBeAnEmojiDoesNot() {
        assertEquals(false, wantsTextPresentation("\u23F8\uFE0F"))
        assertEquals(false, wantsTextPresentation("\u2764\uFE0F"))
    }

    @Test
    fun whatIsNotAnEmojiDoesNot() {
        for (text in listOf("", "a", "1", "\u00E9", "\u2500", "\u23F5")) {
            assertEquals(false, wantsTextPresentation(text), text)
        }
    }

    // A font that lacks the symbol, whichever it is: the fallback is asked about the faces it is given.
    @Test
    fun findsATextFontForAnyFontThatLacksTheSymbol() {
        for (name in listOf("Menlo", "Courier New", "Apple Symbols", "Helvetica")) {
            val font = TerminalFont.system(name) ?: continue

            assertNotNull(font.fallback.familyFor("\u23F8"), "The terminal's font is $name")
        }
    }

    @Test
    fun usesNoFallbackWhenTheTerminalsFontHasTheSymbol() {
        // STIX Two Math has the pause button as text.
        val font = TerminalFont.system("STIX Two Math") ?: return

        assertNull(font.fallback.familyFor("\u23F8"))
    }

    @Test
    fun aFontIsHasWhatAnyOfItsFacesHas() {
        val regular = TerminalFont.bundled().faces
        val symbols = TerminalFont.system("STIX Two Math")?.faces ?: return

        assertNull(TextFallback(regular + symbols).familyFor("\u23F8"), "One of the faces has it")
        assertNotNull(TextFallback(regular).familyFor("\u23F8"), "None of them has it")
    }
}
