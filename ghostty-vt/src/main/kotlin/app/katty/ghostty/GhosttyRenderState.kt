package app.katty.ghostty

import app.katty.ghostty.ffm.GhosttyBuffer
import app.katty.ghostty.ffm.GhosttyColorRgb
import app.katty.ghostty.ffm.GhosttyRenderStateColors
import app.katty.ghostty.ffm.GhosttyRenderStateCursor
import app.katty.ghostty.ffm.GhosttyRenderStateRowSelection
import app.katty.ghostty.ffm.GhosttyStyle
import app.katty.ghostty.ffm.GhosttyVt
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * What it takes to draw a [GhosttyTerminal]: a snapshot of its viewport, colors and cursor, that
 * can be read after the terminal moved on.
 *
 * Only [update] touches the terminal, so it is the only call that needs to be serialized with the
 * thread feeding the terminal. Everything else reads the snapshot.
 *
 * Like the terminal, a render state is not thread-safe.
 */
public class GhosttyRenderState : AutoCloseable {
    private val handle: MemorySegment

    // Native memory that outlives a call: handles, and the scratch structs that are read back.
    private val arena = Arena.ofShared()
    private val rowIterator: MemorySegment
    private val rowCells: MemorySegment

    // A pointer to the handle, which is what the functions that populate them take.
    private val rowIteratorSlot = arena.allocate(ValueLayout.ADDRESS)
    private val rowCellsSlot = arena.allocate(ValueLayout.ADDRESS)

    private val scratch = arena.allocate(8)
    private val scratchId = arena.allocate(16)
    private val scratchSelection = GhosttyRenderStateRowSelection.allocate(arena)
    private val scratchStyle = GhosttyStyle.allocate(arena)
    private val scratchRgb = GhosttyColorRgb.allocate(arena)
    private val scratchText = arena.allocate(INITIAL_TEXT_CAPACITY)
    private val scratchBuffer = GhosttyBuffer.allocate(arena)

    private var closed = false

    init {
        NativeLibrary.ensureLoaded()

        try {
            handle = newHandle { GhosttyVt.ghostty_render_state_new(MemorySegment.NULL, it) }
            rowIterator = newHandle { GhosttyVt.ghostty_render_state_row_iterator_new(MemorySegment.NULL, it) }
            rowCells = newHandle { GhosttyVt.ghostty_render_state_row_cells_new(MemorySegment.NULL, it) }
        } catch (e: Throwable) {
            arena.close()
            throw e
        }
        rowIteratorSlot.set(ValueLayout.ADDRESS, 0, rowIterator)
        rowCellsSlot.set(ValueLayout.ADDRESS, 0, rowCells)
    }

    /**
     * Captures the current state of [terminal].
     *
     * This is the only call that must not run concurrently with the use of the terminal.
     */
    public fun update(terminal: GhosttyTerminal) {
        requireOpen()
        terminal.requireOpen()
        checkResult(GhosttyVt.ghostty_render_state_update(handle, terminal.handle), "ghostty_render_state_update")
    }

    /**
     * Declares that what [dirty] and [GhosttyRenderRow.dirty] report has been drawn.
     */
    public fun clean() {
        requireOpen()
        checkResult(GhosttyVt.ghostty_render_state_clean(handle), "ghostty_render_state_clean")
    }

    public val cols: Int get() = getUInt16(GhosttyVt.GHOSTTY_RENDER_STATE_DATA_COLS())

    public val rows: Int get() = getUInt16(GhosttyVt.GHOSTTY_RENDER_STATE_DATA_ROWS())

    public val dirty: GhosttyDirty
        get() {
            requireOpen()
            checkResult(
                GhosttyVt.ghostty_render_state_get(handle, GhosttyVt.GHOSTTY_RENDER_STATE_DATA_DIRTY(), scratch),
                "ghostty_render_state_get(DIRTY)",
            )
            return when (scratch.get(ValueLayout.JAVA_INT, 0)) {
                GhosttyVt.GHOSTTY_RENDER_STATE_DIRTY_FALSE() -> GhosttyDirty.CLEAN
                GhosttyVt.GHOSTTY_RENDER_STATE_DIRTY_PARTIAL() -> GhosttyDirty.PARTIAL
                else -> GhosttyDirty.FULL
            }
        }

    public val colors: GhosttyRenderColors
        get() = withColors { colors ->
            GhosttyRenderColors(
                background = readRgb(GhosttyRenderStateColors.background(colors)),
                foreground = readRgb(GhosttyRenderStateColors.foreground(colors)),
                cursor = if (GhosttyRenderStateColors.cursor_has_value(colors)) {
                    readRgb(GhosttyRenderStateColors.cursor(colors))
                } else {
                    null
                },
            )
        }

    /**
     * The 256 colors that a cell's palette index refers to.
     *
     * Cells already carry their resolved colors, so most renderers never need it: it is read when asked,
     * not along with [colors].
     */
    public val palette: List<GhosttyRgb>
        get() = withColors { colors ->
            List(PALETTE_SIZE) { readRgb(GhosttyRenderStateColors.palette(colors, it.toLong())) }
        }

    public val cursor: GhosttyRenderCursor
        get() {
            requireOpen()
            return Arena.ofConfined().use { local ->
                val cursor = GhosttyRenderStateCursor.allocate(local)
                GhosttyRenderStateCursor.size(cursor, GhosttyRenderStateCursor.sizeof())
                checkResult(
                    GhosttyVt.ghostty_render_state_get(handle, GhosttyVt.GHOSTTY_RENDER_STATE_DATA_CURSOR(), cursor),
                    "ghostty_render_state_get(CURSOR)",
                )
                val inViewport = GhosttyRenderStateCursor.viewport_has_value(cursor)
                GhosttyRenderCursor(
                    x = if (inViewport) GhosttyRenderStateCursor.viewport_x(cursor).toInt() and 0xFFFF else null,
                    y = if (inViewport) GhosttyRenderStateCursor.viewport_y(cursor).toInt() and 0xFFFF else null,
                    visible = GhosttyRenderStateCursor.visible(cursor),
                    blinking = GhosttyRenderStateCursor.blinking(cursor),
                    style = when (GhosttyRenderStateCursor.visual_style(cursor)) {
                        GhosttyVt.GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BAR() -> GhosttyCursorStyle.BAR
                        GhosttyVt.GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_UNDERLINE() -> GhosttyCursorStyle.UNDERLINE
                        GhosttyVt.GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK_HOLLOW() -> GhosttyCursorStyle.BLOCK_HOLLOW
                        else -> GhosttyCursorStyle.BLOCK
                    },
                    wideTail = GhosttyRenderStateCursor.wide_tail(cursor),
                    passwordInput = GhosttyRenderStateCursor.password_input(cursor),
                )
            }
        }

    /**
     * Reads the rows of the viewport, from the top.
     *
     * Reading the cells is the costly part, so [readCellsOf] decides, for each row, if it is done:
     * a renderer that caches what it prepared for a row would answer
     * `dirty || id !in cache`. The cells of a row for which it answers `false` are `null`.
     *
     * @param readCellsOf Called with the id of the row and whether it is [GhosttyRenderRow.dirty].
     * Everything is read by default.
     */
    public fun readRows(readCellsOf: (id: GhosttyRowId, dirty: Boolean) -> Boolean = { _, _ -> true }): List<GhosttyRenderRow> {
        requireOpen()
        checkResult(
            GhosttyVt.ghostty_render_state_get(handle, GhosttyVt.GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR(), rowIteratorSlot),
            "ghostty_render_state_get(ROW_ITERATOR)",
        )
        val everythingDirty = dirty == GhosttyDirty.FULL

        val rows = ArrayList<GhosttyRenderRow>(this.rows)
        while (GhosttyVt.ghostty_render_state_row_iterator_next(rowIterator)) {
            checkResult(
                GhosttyVt.ghostty_render_state_row_get(rowIterator, GhosttyVt.GHOSTTY_RENDER_STATE_ROW_DATA_VIEWPORT_Y(), scratch),
                "ghostty_render_state_row_get(VIEWPORT_Y)",
            )
            val y = scratch.get(ValueLayout.JAVA_INT, 0)

            checkResult(
                GhosttyVt.ghostty_render_state_row_get(rowIterator, GhosttyVt.GHOSTTY_RENDER_STATE_ROW_DATA_ID(), scratchId),
                "ghostty_render_state_row_get(ID)",
            )
            val id = GhosttyRowId(scratchId.get(ValueLayout.JAVA_LONG, 0), scratchId.get(ValueLayout.JAVA_LONG, 8))

            checkResult(
                GhosttyVt.ghostty_render_state_row_get(rowIterator, GhosttyVt.GHOSTTY_RENDER_STATE_ROW_DATA_DIRTY(), scratch),
                "ghostty_render_state_row_get(DIRTY)",
            )
            val dirty = everythingDirty || scratch.get(ValueLayout.JAVA_BOOLEAN, 0)

            GhosttyRenderStateRowSelection.size(scratchSelection, GhosttyRenderStateRowSelection.sizeof())
            val selectionResult = GhosttyVt.ghostty_render_state_row_get(
                rowIterator,
                GhosttyVt.GHOSTTY_RENDER_STATE_ROW_DATA_SELECTION(),
                scratchSelection,
            )
            val selection = when (selectionResult) {
                GhosttyVt.GHOSTTY_NO_VALUE() -> null
                else -> {
                    checkResult(selectionResult, "ghostty_render_state_row_get(SELECTION)")
                    val start = GhosttyRenderStateRowSelection.start_x(scratchSelection).toInt() and 0xFFFF
                    val end = GhosttyRenderStateRowSelection.end_x(scratchSelection).toInt() and 0xFFFF
                    start..end
                }
            }

            val cells = if (readCellsOf(id, dirty)) readCells() else null
            rows += GhosttyRenderRow(y, id, dirty, selection, cells)
        }
        return rows
    }

    override fun close() {
        if (closed) return
        closed = true
        GhosttyVt.ghostty_render_state_row_cells_free(rowCells)
        GhosttyVt.ghostty_render_state_row_iterator_free(rowIterator)
        GhosttyVt.ghostty_render_state_free(handle)
        arena.close()
    }

    private fun readCells(): List<GhosttyRenderCell> {
        checkResult(
            GhosttyVt.ghostty_render_state_row_get(rowIterator, GhosttyVt.GHOSTTY_RENDER_STATE_ROW_DATA_CELLS(), rowCellsSlot),
            "ghostty_render_state_row_get(CELLS)",
        )
        val cells = ArrayList<GhosttyRenderCell>(cols)
        while (GhosttyVt.ghostty_render_state_row_cells_next(rowCells)) {
            cells += readCell()
        }
        return cells
    }

    private fun readCell(): GhosttyRenderCell {
        val text = readCellText()

        val hasStyling = cellValue(GhosttyVt.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_HAS_STYLING(), scratch) == GhosttyVt.GHOSTTY_SUCCESS() &&
            scratch.get(ValueLayout.JAVA_BOOLEAN, 0)
        val style = if (hasStyling) readStyle() else GhosttyCellStyle.NONE

        val foreground = readCellColor(GhosttyVt.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR())
        val background = readCellColor(GhosttyVt.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR())

        checkResult(cellValue(GhosttyVt.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW(), scratch), "ghostty_render_state_row_cells_get(RAW)")
        checkResult(
            GhosttyVt.ghostty_cell_get(scratch.get(ValueLayout.JAVA_LONG, 0), GhosttyVt.GHOSTTY_CELL_DATA_WIDE(), scratch),
            "ghostty_cell_get(WIDE)",
        )
        val width = when (scratch.get(ValueLayout.JAVA_INT, 0)) {
            GhosttyVt.GHOSTTY_CELL_WIDE_WIDE() -> GhosttyCellWidth.WIDE
            GhosttyVt.GHOSTTY_CELL_WIDE_SPACER_TAIL() -> GhosttyCellWidth.SPACER_TAIL
            GhosttyVt.GHOSTTY_CELL_WIDE_SPACER_HEAD() -> GhosttyCellWidth.SPACER_HEAD
            else -> GhosttyCellWidth.NARROW
        }

        return GhosttyRenderCell(text, foreground, background, style, width)
    }

    private fun readCellText(): String {
        GhosttyBuffer.ptr(scratchBuffer, scratchText)
        GhosttyBuffer.cap(scratchBuffer, scratchText.byteSize())
        GhosttyBuffer.len(scratchBuffer, 0)
        var result = cellValue(GhosttyVt.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_UTF8(), scratchBuffer)
        if (result == GhosttyVt.GHOSTTY_OUT_OF_SPACE()) {
            // A very long grapheme cluster: the length is the capacity that is needed.
            return Arena.ofConfined().use { local ->
                val bigger = local.allocate(GhosttyBuffer.len(scratchBuffer))
                GhosttyBuffer.ptr(scratchBuffer, bigger)
                GhosttyBuffer.cap(scratchBuffer, bigger.byteSize())
                result = cellValue(GhosttyVt.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_UTF8(), scratchBuffer)
                checkResult(result, "ghostty_render_state_row_cells_get(GRAPHEMES_UTF8)")
                decodeText(bigger, GhosttyBuffer.len(scratchBuffer))
            }
        }
        checkResult(result, "ghostty_render_state_row_cells_get(GRAPHEMES_UTF8)")
        return decodeText(scratchText, GhosttyBuffer.len(scratchBuffer))
    }

    private fun decodeText(buffer: MemorySegment, length: Long): String {
        if (length == 0L) return ""
        return buffer.asSlice(0, length).toArray(ValueLayout.JAVA_BYTE).decodeToString()
    }

    private fun readStyle(): GhosttyCellStyle {
        GhosttyStyle.size(scratchStyle, GhosttyStyle.sizeof())
        checkResult(cellValue(GhosttyVt.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE(), scratchStyle), "ghostty_render_state_row_cells_get(STYLE)")
        return GhosttyCellStyle(
            bold = GhosttyStyle.bold(scratchStyle),
            italic = GhosttyStyle.italic(scratchStyle),
            faint = GhosttyStyle.faint(scratchStyle),
            blink = GhosttyStyle.blink(scratchStyle),
            inverse = GhosttyStyle.inverse(scratchStyle),
            invisible = GhosttyStyle.invisible(scratchStyle),
            strikethrough = GhosttyStyle.strikethrough(scratchStyle),
            overline = GhosttyStyle.overline(scratchStyle),
            underline = GhosttyUnderline.entries.getOrElse(GhosttyStyle.underline(scratchStyle)) { GhosttyUnderline.SINGLE },
        )
    }

    // Null when the cell has no explicit color: GHOSTTY_INVALID_VALUE is the answer, not an error.
    private fun readCellColor(data: Int): GhosttyRgb? =
        if (cellValue(data, scratchRgb) == GhosttyVt.GHOSTTY_SUCCESS()) readRgb(scratchRgb) else null

    private fun cellValue(data: Int, out: MemorySegment): Int = GhosttyVt.ghostty_render_state_row_cells_get(rowCells, data, out)

    private fun readRgb(rgb: MemorySegment): GhosttyRgb =
        GhosttyRgb.of(
            GhosttyColorRgb.r(rgb).toInt(),
            GhosttyColorRgb.g(rgb).toInt(),
            GhosttyColorRgb.b(rgb).toInt(),
        )

    private fun getUInt16(data: Int): Int {
        requireOpen()
        checkResult(GhosttyVt.ghostty_render_state_get(handle, data, scratch), "ghostty_render_state_get")
        return scratch.get(ValueLayout.JAVA_SHORT, 0).toInt() and 0xFFFF
    }

    private fun <T> withColors(read: (MemorySegment) -> T): T {
        requireOpen()
        return Arena.ofConfined().use { local ->
            val colors = GhosttyRenderStateColors.allocate(local)
            GhosttyRenderStateColors.size(colors, GhosttyRenderStateColors.sizeof())
            checkResult(
                GhosttyVt.ghostty_render_state_get(handle, GhosttyVt.GHOSTTY_RENDER_STATE_DATA_COLORS(), colors),
                "ghostty_render_state_get(COLORS)",
            )
            read(colors)
        }
    }

    private fun requireOpen() {
        check(!closed) { "The render state is closed" }
    }

    private fun newHandle(create: (MemorySegment) -> Int): MemorySegment =
        Arena.ofConfined().use { local ->
            val out = local.allocate(ValueLayout.ADDRESS)
            checkResult(create(out), "creating a render state object")
            out.get(ValueLayout.ADDRESS, 0)
        }

    private companion object {
        const val PALETTE_SIZE = 256
        const val INITIAL_TEXT_CAPACITY = 256L
    }
}
