package app.katty.terminal.themes

import app.katty.terminal.TerminalColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ITermColorSchemesTest {
    @Test
    fun listsEveryScheme() {
        val names = ITermColorSchemes.names

        assertEquals(811, names.size)
        assertEquals(names.size, names.toSet().size)
        assertTrue("Alabaster" in names)
        assertTrue("Dracula" in names)
        assertEquals(names.sortedWith(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder())), names, "They are sorted")
    }

    @Test
    fun hasAnEntryForEachSchemeInTheOrderOfTheNames() {
        assertEquals(ITermColorSchemes.names, ITermColorSchemes.entries.map { it.name })
    }

    @Test
    fun theEntriesHaveTheColorsThatLoadingTheSchemeGives() {
        val differences = ITermColorSchemes.entries.mapNotNull { entry ->
            val theme = ITermColorSchemes.load(entry.name)
            val expected = listOf(theme.background, theme.foreground) + theme.ansi.subList(1, 7)
            val actual = listOf(entry.background, entry.foreground) + entry.accents
            if (expected == actual) null else entry.name
        }

        assertEquals(emptyList(), differences)
    }

    @Test
    fun knowsWhichSchemesAreDark() {
        val byName = ITermColorSchemes.entries.associateBy { it.name }

        assertTrue(byName.getValue("Dracula").dark)
        assertTrue(byName.getValue("iTerm2 Solarized Dark").dark)
        assertTrue(!byName.getValue("iTerm2 Solarized Light").dark)
        assertTrue(!byName.getValue("Alabaster").dark)
        // Both kinds are there, and not only a few of one.
        assertTrue(ITermColorSchemes.entries.count { it.dark } in 200..700)
    }

    @Test
    fun readsEveryScheme() {
        // Reports all the ones that cannot be read, not only the first.
        val failures = ITermColorSchemes.names.mapNotNull { name ->
            try {
                assertEquals(16, ITermColorSchemes.load(name).ansi.size)
                null
            } catch (e: Exception) {
                "$name: ${e.message}"
            }
        }

        assertEquals(emptyList(), failures)
    }

    @Test
    fun readsTheColorsOfAScheme() {
        val theme = ITermColorSchemes.load("Alabaster")

        assertEquals(TerminalColor(0xF7F7F7), theme.background)
        assertEquals(TerminalColor(0x000000), theme.foreground)
        assertEquals(TerminalColor(0xAA3731), theme.ansi[1])
        assertEquals(TerminalColor(0xF7F7F7), theme.ansi[15])
        assertEquals(TerminalColor(0x007ACC), theme.cursor)
        assertEquals(TerminalColor(0xBFDBFE), theme.cursorText)
        assertEquals(TerminalColor(0x000000), theme.bold)
        assertEquals(TerminalColor(0xBFDBFE), theme.selection)
        assertEquals(TerminalColor(0x000000), theme.selectedText)
    }

    @Test
    fun theOptionalColorsAreNullWhenTheSchemeHasNone() {
        // At least one scheme does not define all of them, and it is still a theme.
        val withoutBold = ITermColorSchemes.names.map { ITermColorSchemes.load(it) }.firstOrNull { it.bold == null }

        assertTrue(withoutBold != null, "No scheme without a bold color: this test needs another optional color")
    }

    @Test
    fun aColorComponentIsRoundedToAByte() {
        // 0.1294 * 255 is 32.997, 0.1333 * 255 is 33.9915 and 0.1725 * 255 is 43.99.
        val theme = parseITermColorScheme(scheme(background = color(0.1294, 0.1333, 0.1725)))

        assertEquals(TerminalColor((33 shl 16) or (34 shl 8) or 44), theme.background)
    }

    @Test
    fun aMissingColorIsReportedByName() {
        val error = assertFailsWith<IllegalArgumentException> { parseITermColorScheme(scheme(background = null)) }

        assertTrue("Background Color" in error.message!!, error.message)
    }

    @Test
    fun aTextThatIsNotAPropertyListIsRejected() {
        assertFailsWith<IllegalArgumentException> { parseITermColorScheme("not xml") }
    }

    @Test
    fun anUnknownSchemeIsRejected() {
        val error = assertFailsWith<IllegalArgumentException> { ITermColorSchemes.load("No such scheme") }

        assertTrue("No such scheme" in error.message!!)
    }

    @Test
    fun aNameThatLooksLikeAPathIsRejected() {
        assertFailsWith<IllegalArgumentException> { ITermColorSchemes.load("../../../../META-INF/MANIFEST") }
    }

    private fun color(r: Double, g: Double, b: Double) = """
        <dict>
            <key>Alpha Component</key><real>1</real>
            <key>Blue Component</key><real>$b</real>
            <key>Color Space</key><string>sRGB</string>
            <key>Green Component</key><real>$g</real>
            <key>Red Component</key><real>$r</real>
        </dict>
    """.trimIndent()

    // The smallest scheme there is, in the layout of the files: a declaration, a comment and a doctype.
    private fun scheme(background: String?): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("<!-- A comment, as many schemes have -->")
        appendLine("""<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">""")
        appendLine("""<plist version="1.0">""")
        appendLine("<dict>")
        repeat(16) { appendLine("<key>Ansi $it Color</key>\n${color(0.0, 0.0, 0.0)}") }
        if (background != null) appendLine("<key>Background Color</key>\n$background")
        appendLine("<key>Foreground Color</key>\n${color(1.0, 1.0, 1.0)}")
        appendLine("</dict>")
        appendLine("</plist>")
    }
}
