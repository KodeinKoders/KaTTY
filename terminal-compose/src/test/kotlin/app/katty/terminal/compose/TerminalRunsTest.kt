package app.katty.terminal.compose

import app.katty.terminal.TerminalCell
import app.katty.terminal.TerminalCellWidth
import app.katty.terminal.TerminalColor
import app.katty.terminal.TerminalRow
import app.katty.terminal.TerminalRowId
import app.katty.terminal.TerminalStyle
import app.katty.terminal.TerminalUnderline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private val FOREGROUND = TerminalColor(0xEEEEEE)
private val BACKGROUND = TerminalColor(0x111111)

private val RED = TerminalColor(0xFF0000)
private val BLUE = TerminalColor(0x0000FF)

private fun cell(
    text: String,
    foreground: TerminalColor? = null,
    background: TerminalColor? = null,
    style: TerminalStyle = TerminalStyle.NONE,
    width: TerminalCellWidth = TerminalCellWidth.NARROW,
) = TerminalCell(text, foreground, background, style, width)

private fun row(vararg cells: TerminalCell) = TerminalRow(TerminalRowId(0, 0), cells.toList())

private fun row(text: String, foreground: TerminalColor? = null, style: TerminalStyle = TerminalStyle.NONE) =
    row(*text.map { cell(it.toString(), foreground, style = style) }.toTypedArray())

private fun runs(row: TerminalRow) = textRuns(row, FOREGROUND, BACKGROUND)

class TerminalRunsTest {
    @Test
    fun plainTextIsOneRun() {
        val run = runs(row("hello world")).single()

        assertEquals("hello world", run.text)
        assertEquals(0, run.startCol)
        assertEquals(11, run.cols)
        assertEquals(FOREGROUND.rgb, run.foreground)
    }

    @Test
    fun aRunEndsWhereTheColorOrTheFontChanges() {
        val row = row(
            cell("a"), cell("b", foreground = RED), cell("c", foreground = RED),
            cell("d", style = TerminalStyle(bold = true)), cell("e", style = TerminalStyle(underline = TerminalUnderline.CURLY)),
        )

        val runs = runs(row)

        assertEquals(listOf("a", "bc", "d", "e"), runs.map { it.text })
        assertEquals(listOf(0, 1, 3, 4), runs.map { it.startCol })
        assertEquals(RED.rgb, runs[1].foreground)
        assertEquals(true, runs[2].bold)
        assertEquals(TerminalUnderline.CURLY, runs[3].underline)
    }

    @Test
    fun cellsWithoutTextBreakARun() {
        val runs = runs(row(cell("a"), cell(""), cell("b")))

        assertEquals(listOf("a", "b"), runs.map { it.text })
        assertEquals(listOf(0, 2), runs.map { it.startCol })
    }

    @Test
    fun whatIsNotPlainAsciiIsDrawnOnItsOwn() {
        // Another font may draw it, with a width that is not a cell's: it must not move what follows.
        val runs = runs(row(cell("a"), cell("é"), cell("b"), cell("─"), cell("c")))

        assertEquals(listOf("a", "é", "b", "─", "c"), runs.map { it.text })
        assertEquals(listOf(0, 1, 2, 3, 4), runs.map { it.startCol })
    }

    @Test
    fun aWideCharacterCoversTwoCellsAndItsSpacerIsSkipped() {
        val runs = runs(
            row(
                cell("a"),
                cell("界", width = TerminalCellWidth.WIDE),
                cell("", width = TerminalCellWidth.SPACER_TAIL),
                cell("b"),
            ),
        )

        assertEquals(listOf("a", "界", "b"), runs.map { it.text })
        assertEquals(listOf(0, 1, 3), runs.map { it.startCol })
        assertEquals(2, runs[1].cols)
    }

    @Test
    fun aGraphemeClusterIsOneRun() {
        val runs = runs(row(cell("é")))

        assertEquals("é", runs.single().text)
        assertEquals(1, runs.single().cols)
    }

    @Test
    fun inverseSwapsTheColors() {
        val colors = resolveColors(cell("a", foreground = RED, background = BLUE, style = TerminalStyle(inverse = true)), FOREGROUND, BACKGROUND)

        assertEquals(CellColors(foreground = BLUE.rgb, background = RED.rgb), colors)
    }

    @Test
    fun inverseSwapsTheDefaultColorsToo() {
        val colors = resolveColors(cell("a", style = TerminalStyle(inverse = true)), FOREGROUND, BACKGROUND)

        assertEquals(CellColors(foreground = BACKGROUND.rgb, background = FOREGROUND.rgb), colors)
    }

    @Test
    fun boldTextThatHasTheDefaultForegroundTakesTheBoldColor() {
        val boldColor = TerminalColor(0xFFFFFF)
        val bold = TerminalStyle(bold = true)

        assertEquals(boldColor.rgb, resolveColors(cell("a", style = bold), FOREGROUND, BACKGROUND, boldColor).foreground)
        assertEquals(RED.rgb, resolveColors(cell("a", foreground = RED, style = bold), FOREGROUND, BACKGROUND, boldColor).foreground, "A color that the program chose is its own")
        assertEquals(FOREGROUND.rgb, resolveColors(cell("a"), FOREGROUND, BACKGROUND, boldColor).foreground, "Text that is not bold is not concerned")
        assertEquals(FOREGROUND.rgb, resolveColors(cell("a", style = bold), FOREGROUND, BACKGROUND).foreground, "Without a bold color, it is the foreground")
    }

    @Test
    fun invisibleTextHasTheColorOfItsBackground() {
        val colors = resolveColors(cell("a", foreground = RED, style = TerminalStyle(invisible = true)), FOREGROUND, BACKGROUND)

        assertEquals(CellColors(foreground = BACKGROUND.rgb, background = BACKGROUND.rgb), colors)
    }

    @Test
    fun faintTextIsHalfwayToItsBackground() {
        val colors = resolveColors(
            cell("a", foreground = TerminalColor(0xFFFFFF), background = TerminalColor(0x000000), style = TerminalStyle(faint = true)),
            FOREGROUND,
            BACKGROUND,
        )

        assertEquals(0x7F7F7F, colors.foreground)
    }

    @Test
    fun backgroundsAreRunsOfCellsThatShareOne() {
        val row = row(
            cell("a"), cell("b", background = RED), cell("c", background = RED), cell("d", background = BLUE),
            cell("e"), cell("f", background = BLUE),
        )

        val runs = backgroundRuns(row, FOREGROUND, BACKGROUND)

        assertEquals(
            listOf(BackgroundRun(1, 2, RED.rgb), BackgroundRun(3, 1, BLUE.rgb), BackgroundRun(5, 1, BLUE.rgb)),
            runs,
            "The default background is not drawn, and two runs of a color with a cell between them are two",
        )
    }

    @Test
    fun anInverseCellHasTheForegroundAsItsBackground() {
        val runs = backgroundRuns(row(cell("a", style = TerminalStyle(inverse = true))), FOREGROUND, BACKGROUND)

        assertEquals(listOf(BackgroundRun(0, 1, FOREGROUND.rgb)), runs)
    }

    @Test
    fun textThatIsHardToReadIsMovedUntilItHasTheMinimumContrast() {
        val darkBlue = TerminalColor(0x00005F)
        val cell = cell("x", darkBlue)

        val plain = resolveColors(cell, FOREGROUND, BACKGROUND).foreground
        val readable = resolveColors(cell, FOREGROUND, BACKGROUND, minimumContrast = 4.5f).foreground

        assertEquals(darkBlue.rgb, plain, "Without a minimum it is what the program asked")
        assertTrue(contrastRatio(darkBlue.rgb, BACKGROUND.rgb) < 4.5f)
        assertTrue(contrastRatio(readable, BACKGROUND.rgb) >= 4.5f, "It is readable now: ${"%06x".format(readable)}")
        // The least that it takes: not all the way to white.
        assertNotEquals(0xFFFFFF, readable)
    }

    @Test
    fun textThatIsReadableIsLeftAlone() {
        assertEquals(RED.rgb, resolveColors(cell("x", RED), FOREGROUND, BACKGROUND, minimumContrast = 3f).foreground)
    }

    @Test
    fun theMinimumContrastGoesTheWayThatCanBeRead() {
        // Light grey on white can only be made darker.
        val readable = withMinimumContrast(0xDDDDDD, 0xFFFFFF, 4.5f)

        assertTrue(readable < 0xDDDDDD)
        assertTrue(contrastRatio(readable, 0xFFFFFF) >= 4.5f)
    }

    @Test
    fun theMinimumContrastGoesAsFarAsItCanWhenItCannotBeMet() {
        // Nothing is 21 times lighter than mid grey: black on it is the most there is.
        assertEquals(0x000000, withMinimumContrast(0x777777, 0x777777, 21f))
    }
}
