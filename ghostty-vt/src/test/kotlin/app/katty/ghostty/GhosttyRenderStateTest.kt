package app.katty.ghostty

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GhosttyRenderStateTest {
    private val terminal = GhosttyTerminal(cols = 20, rows = 5)
    private val state = GhosttyRenderState()

    @AfterTest
    fun close() {
        state.close()
        terminal.close()
    }

    private fun render(text: String): List<GhosttyRenderRow> {
        terminal.vtWrite(text)
        state.update(terminal)
        return state.readRows()
    }

    @Test
    fun readsTextAndTheCursor() {
        val rows = render("hello\r\n\u001b[31mred")

        assertEquals(5, rows.size)
        assertEquals(listOf(0, 1, 2, 3, 4), rows.map { it.y })
        assertEquals(20, rows[0].cells!!.size)
        assertEquals("hello", rows[0].cells!!.take(5).joinToString("") { it.text })
        assertEquals("", rows[0].cells!![5].text, "A cell without text has an empty text")
        assertEquals("red", rows[1].cells!!.take(3).joinToString("") { it.text })

        val cursor = state.cursor
        assertEquals(3, cursor.x)
        assertEquals(1, cursor.y)
        assertTrue(cursor.visible)
        assertEquals(GhosttyCursorStyle.BLOCK, cursor.style)

        assertEquals(20, state.cols)
        assertEquals(5, state.rows)
    }

    @Test
    fun resolvesColors() {
        val rows = render("a\u001b[31mb\u001b[38;2;255;100;0mc\u001b[44md")
        val cells = rows[0].cells!!
        val palette = state.palette

        assertNull(cells[0].foreground, "The default foreground is not an explicit color")
        assertNull(cells[0].background)
        assertEquals(palette[1], cells[1].foreground, "SGR 31 is the palette's red")
        assertEquals(GhosttyRgb.of(255, 100, 0), cells[2].foreground)
        assertEquals(palette[4], cells[3].background, "SGR 44 is the palette's blue")
        assertEquals(256, palette.size)
    }

    @Test
    fun readsStyles() {
        val rows = render("\u001b[1;3;4mx\u001b[0m\u001b[7;9my\u001b[0mz")
        val cells = rows[0].cells!!

        assertEquals(
            GhosttyCellStyle(bold = true, italic = true, underline = GhosttyUnderline.SINGLE),
            cells[0].style,
        )
        assertEquals(GhosttyCellStyle(inverse = true, strikethrough = true), cells[1].style)
        assertEquals(GhosttyCellStyle.NONE, cells[2].style)
    }

    @Test
    fun readsWideCharactersAndGraphemeClusters() {
        // A wide character takes two cells, and an emoji with a skin tone modifier is one cluster.
        val rows = render("界aé")
        val cells = rows[0].cells!!

        assertEquals(GhosttyCellWidth.WIDE, cells[0].width)
        assertEquals("界", cells[0].text)
        assertEquals(GhosttyCellWidth.SPACER_TAIL, cells[1].width)
        assertEquals("a", cells[2].text)
        assertEquals(GhosttyCellWidth.NARROW, cells[2].width)
        assertEquals("é", cells[3].text, "A combining accent stays with its base character")
    }

    @Test
    fun tracksWhatChanged() {
        val first = render("one\r\ntwo")
        assertEquals(GhosttyDirty.FULL, state.dirty)
        assertTrue(first.all { it.dirty })

        state.clean()
        assertEquals(GhosttyDirty.CLEAN, state.dirty)

        // Only the line where the cursor is has changed.
        terminal.vtWrite("!")
        state.update(terminal)
        assertEquals(GhosttyDirty.PARTIAL, state.dirty)
        val second = state.readRows { _, dirty -> dirty }
        assertEquals(listOf(1), second.filter { it.dirty }.map { it.y })
        assertNotNull(second[1].cells)
        assertNull(second[0].cells, "The cells of a row that was not asked for are not read")
        assertEquals(first[0].id, second[0].id, "A row keeps its id when it does not change")
    }

    @Test
    fun replacesAScrolledRowsId() {
        val first = render("one")
        terminal.vtWrite("\r\n\r\n\r\n\r\n\r\ntail")
        state.update(terminal)
        val second = state.readRows()

        assertEquals("tail", second.last().cells!!.take(4).joinToString("") { it.text })
        assertFalse(first[0].id in second.map { it.id }, "The row that scrolled off is gone")
    }

    @Test
    fun resizesWithReflow() {
        terminal.vtWrite("0123456789abcdefghij0123456789")
        terminal.resize(cols = 40, rows = 3, cellWidthPx = 8, cellHeightPx = 16)
        state.update(terminal)

        assertEquals(40, terminal.cols)
        assertEquals(3, terminal.rows)
        assertEquals(40, state.cols)
        assertEquals(3, state.rows)
        assertEquals(
            "0123456789abcdefghij0123456789",
            state.readRows()[0].cells!!.take(30).joinToString("") { it.text },
            "The wrapped line is a single one again once it fits",
        )
    }

    @Test
    fun answersQueriesThroughOnWritePty() {
        val written = mutableListOf<ByteArray>()
        terminal.onWritePty = { written += it }

        // Device status report: the terminal answers that it is fine.
        terminal.vtWrite("\u001b[5n")

        assertContentEquals("\u001b[0n".encodeToByteArray(), written.flatMap { it.toList() }.toByteArray())
    }

    @Test
    fun reportsModes() {
        assertFalse(terminal.isModeEnabled(GhosttyMode.CURSOR_KEYS))
        terminal.vtWrite("\u001b[?1h")
        assertTrue(terminal.isModeEnabled(GhosttyMode.CURSOR_KEYS))
        assertTrue(terminal.isModeEnabled(GhosttyMode.CURSOR_VISIBLE))
    }

    @Test
    fun packsModes() {
        assertEquals(1004, GhosttyMode.FOCUS_EVENT.value)
        assertFalse(GhosttyMode.FOCUS_EVENT.isAnsi)
        assertEquals(4, GhosttyMode.ansi(4).value)
        assertTrue(GhosttyMode.ansi(4).isAnsi)
    }
}
