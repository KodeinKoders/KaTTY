package app.katty.terminal.compose

import app.katty.terminal.TerminalCommandMark
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommandMarksUiTest {
    private val start = Instant.parse("2026-11-04T13:22:05Z")
    private val paris = ZoneId.of("Europe/Paris")

    private fun mark(id: Long, top: Int, bottom: Int = top, exitCode: Int? = 0, took: Duration? = Duration.ofSeconds(72)) =
        TerminalCommandMark(id, top, bottom, exitCode, start, took?.let { start + it })

    // The gutter is 14 pixels wide, the padding above is 4, and a row is 20 pixels tall.
    private fun CommandMarkInteraction.at(x: Float, y: Float, marks: List<TerminalCommandMark>, press: Boolean = false) =
        handle(x, y, press, marks, gutter = 14f, top = 4f, cellHeight = 20f)

    private fun rowY(row: Int) = 4f + row * 20f + 5f

    @Test
    fun theMarkOfTheRowsThatThePointerIsOnInTheGutterIsHovered() {
        val interaction = CommandMarkInteraction()
        val marks = listOf(mark(1, 0, 1), mark(2, 3, 6))

        interaction.at(7f, rowY(5), marks)

        assertEquals(2L, interaction.hovered)
        assertEquals(5, interaction.hoveredRow)
        interaction.at(7f, rowY(1), marks)
        assertEquals(1L, interaction.hovered, "Anywhere along the bar, not only on its first row")
    }

    @Test
    fun noMarkIsHoveredOnARowThatNoCommandIsOnOrOutsideTheGutter() {
        val interaction = CommandMarkInteraction()
        val marks = listOf(mark(1, 0, 1))

        interaction.at(7f, rowY(2), marks)
        assertNull(interaction.hovered, "Row 2 is under the bar")

        interaction.at(7f, rowY(1), marks)
        assertEquals(1L, interaction.hovered)
        interaction.at(14f, rowY(1), marks)
        assertNull(interaction.hovered, "The grid starts at 14")
    }

    @Test
    fun noMarkIsHoveredAboveTheGrid() {
        val interaction = CommandMarkInteraction()

        interaction.at(7f, 2f, listOf(mark(1, 0)))

        assertNull(interaction.hovered)
    }

    @Test
    fun aPressOnAMarkGivesItAndAMoveDoesNot() {
        val interaction = CommandMarkInteraction()
        val marks = listOf(mark(1, 0, 2))

        assertNull(interaction.at(7f, rowY(1), marks))
        assertEquals(1L, interaction.at(7f, rowY(2), marks, press = true)?.id)
        assertNull(interaction.at(30f, rowY(1), marks, press = true), "A press on the grid is not the mark's")
    }

    @Test
    fun theMarkUnderThePointerIsReadAgainWhenTheScreenMoves() {
        val interaction = CommandMarkInteraction()
        interaction.at(7f, rowY(0), listOf(mark(1, 0)))

        // The history scrolled: another command is under the pointer.
        interaction.refresh(listOf(mark(2, 0)), gutter = 14f, top = 4f, cellHeight = 20f)
        assertEquals(2L, interaction.hovered)

        interaction.refresh(emptyList(), gutter = 14f, top = 4f, cellHeight = 20f)
        assertNull(interaction.hovered)
    }

    @Test
    fun nothingIsHoveredOnceThePointerLeaves() {
        val interaction = CommandMarkInteraction()
        interaction.at(7f, rowY(0), listOf(mark(1, 0)))

        interaction.exit()
        interaction.refresh(listOf(mark(1, 0)), gutter = 14f, top = 4f, cellHeight = 20f)

        assertNull(interaction.hovered, "A pointer that left is not over the mark that comes where it was")
    }

    private fun frameWith(marks: List<TerminalCommandMark>, scrollbar: app.katty.terminal.TerminalScrollbar?) = app.katty.terminal.TerminalFrame(
        cols = 40,
        rows = emptyList(),
        background = app.katty.terminal.TerminalColor(0x000000),
        foreground = app.katty.terminal.TerminalColor(0xFFFFFF),
        cursorColor = null,
        cursor = app.katty.terminal.TerminalCursor(x = null, y = null, visible = false, blinking = false, shape = app.katty.terminal.TerminalCursorShape.BLOCK),
        scrollbar = scrollbar,
        commandMarks = marks,
    )

    private fun sticky(top: Int = 0, startsAbove: Boolean = true, command: String? = "make test") =
        TerminalCommandMark(7, top, 9, 0, start, start, startsAbove = startsAbove, command = command, startRow = 3)

    // 100 rows, 24 of them in view.
    private fun scrolledBack() = app.katty.terminal.TerminalScrollbar(total = 100, offset = 20, len = 24)
    private fun atTheBottom() = app.katty.terminal.TerminalScrollbar(total = 100, offset = 76, len = 24)

    @Test
    fun theLineOfTheCommandThatTheTopRowBelongsToIsShownWhileTheHistoryIsScrolledThrough() {
        assertEquals(7L, stickyCommand(frameWith(listOf(sticky()), scrolledBack()))?.id)
    }

    @Test
    fun noLineIsShownAtTheBottomWithoutHistoryOrForACommandThatDoesNotStartAbove() {
        assertNull(stickyCommand(frameWith(listOf(sticky()), atTheBottom())), "At the bottom: the screen as the program writes it")
        assertNull(stickyCommand(frameWith(listOf(sticky()), null)), "Nothing to scroll")
        assertNull(stickyCommand(frameWith(listOf(sticky(startsAbove = false)), scrolledBack())), "Its prompt is in view")
        assertNull(stickyCommand(frameWith(listOf(sticky(top = 3)), scrolledBack())), "The top row is not its")
    }

    @Test
    fun noLineIsShownForACommandWhoseLineIsNotKnown() {
        assertNull(stickyCommand(frameWith(listOf(sticky(command = null)), scrolledBack())))
        assertNull(stickyCommand(frameWith(listOf(sticky(command = "  ")), scrolledBack())))
    }

    @Test
    fun aLineThatIsTooLongForTheRowIsCutWithAnEllipsis() {
        assertEquals("make test", stickyText("make test", 40))
        assertEquals("abcdefg…", stickyText("abcdefghijklmnop", 10), "Two columns are left: one at each side")
        assertEquals("…", stickyText("abcdef", 1))
    }

    @Test
    fun theTooltipSaysHowTheCommandWentWhenItStartedAndHowLongItTook() {
        assertEquals(
            listOf("Succeeded", "Started 2026-11-04 14:22:05", "Took 1 min 12 s"),
            commandMarkLines(mark(1, 0), now = start + Duration.ofMinutes(10), zone = paris),
        )
        assertEquals("Failed: exit code 2", commandMarkLines(mark(1, 0, exitCode = 2), start, paris).first())
        assertEquals("Ended", commandMarkLines(mark(1, 0, exitCode = null), start, paris).first())
    }

    @Test
    fun theTooltipOfACommandThatWasStoppedSaysSoAndHowLongItRan() {
        assertEquals(
            listOf("Stopped, in the background", "Started 2026-11-04 14:22:05", "Ran for 1 min 12 s"),
            commandMarkLines(mark(1, 0, exitCode = 146), now = start + Duration.ofMinutes(10), zone = paris),
        )
    }

    @Test
    fun theTooltipOfACommandThatRunsSaysForHowLong() {
        assertEquals(
            listOf("Running", "Started 2026-11-04 14:22:05", "Running for 12 s"),
            commandMarkLines(mark(1, 0, exitCode = null, took = null), now = start + Duration.ofSeconds(12), zone = paris),
        )
    }

    @Test
    fun aDurationIsWrittenInItsTwoLargestUnits() {
        assertEquals("0.4 s", formatCommandDuration(Duration.ofMillis(400)))
        assertEquals("0.0 s", formatCommandDuration(Duration.ofMillis(-5)))
        assertEquals("1 s", formatCommandDuration(Duration.ofMillis(1999)))
        assertEquals("12 s", formatCommandDuration(Duration.ofSeconds(12)))
        assertEquals("1 min 12 s", formatCommandDuration(Duration.ofSeconds(72)))
        assertEquals("2 min", formatCommandDuration(Duration.ofSeconds(120)))
        assertEquals("2 h 5 min", formatCommandDuration(Duration.ofMinutes(125)))
        assertEquals("3 h", formatCommandDuration(Duration.ofHours(3)))
    }
}
