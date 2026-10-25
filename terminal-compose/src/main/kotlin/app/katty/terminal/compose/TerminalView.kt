package app.katty.terminal.compose

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.katty.terminal.TerminalCellWidth
import app.katty.terminal.TerminalCursor
import app.katty.terminal.TerminalCursorShape
import app.katty.terminal.TerminalFrame
import app.katty.terminal.TerminalLink
import app.katty.terminal.TerminalImageLayer
import app.katty.terminal.TerminalScrollbar
import app.katty.terminal.TerminalUnderline
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.round

private const val CURSOR_BLINK_MS = 530L

/**
 * Draws a frame: a pure function of what it is given, which is what makes it possible to draw one that
 * does not come from a session.
 */
@Composable
internal fun TerminalView(
    frame: TerminalFrame,
    typography: TerminalTypography,
    focused: Boolean,
    modifier: Modifier = Modifier,
    preedit: String = "",
    scrollbarAlpha: Float = 0f,
    scrollbarEmphasized: Boolean = false,
    hoveredLink: TerminalLink? = null,
    minimumContrast: Float = 1f,
    builtinGlyphs: Boolean = true,
    /**
     * How far the pulse of the bell is, from 1 (it just rang) to 0 (it is over): a ring around the cursor. Read as it is drawn, so that the pulse
     * does not recompose.
     */
    bellPulse: () -> Float = { 0f },
    /** How opaque the default background that the view paints is: 0 when the one who holds it paints it. */
    defaultBackgroundAlpha: Float = 1f,
) {
    // What is kept for the rows depends on how they are drawn.
    val layouts = remember(typography, frame.foreground, frame.background, frame.boldColor, minimumContrast, builtinGlyphs) {
        RowLayouts(typography, minimumContrast, builtinGlyphs)
    }
    val cursorShown = rememberCursorBlink(frame.cursor, focused)
    val textShown = rememberTextBlink(frame)

    val bitmaps = remember { ImageBitmapCache() }

    Canvas(modifier) {
        if (defaultBackgroundAlpha > 0f) drawRect(frame.background.rgb.toColor().copy(alpha = defaultBackgroundAlpha))

        bitmaps.retain(frame.images.map { it.image })
        // The layers of the images are between what the cells are drawn with, as the protocol says.
        drawImages(frame, TerminalImageLayer.BELOW_BACKGROUND, bitmaps, typography)

        val rows = layouts.prepare(frame)
        rows.forEachIndexed { y, row ->
            val top = y * typography.cellHeight
            for (background in row.backgrounds) {
                drawCells(background.startCol, background.cols, top, typography, background.rgb.toColor())
            }
        }
        drawImages(frame, TerminalImageLayer.BELOW_TEXT, bitmaps, typography)
        rows.forEachIndexed { y, row ->
            val top = y * typography.cellHeight
            for (text in row.texts) {
                // Text that blinks is hidden half of the time.
                if (text.run.blink && !textShown.value) continue
                drawPreparedText(text, top, typography)
            }
            for (sprite in row.sprites) {
                if (sprite.blink && !textShown.value) continue
                drawPreparedSprite(sprite, top, typography.cellHeight)
            }
        }
        hoveredLink?.let { drawLinkUnderline(it, frame.foreground.rgb.toColor(), typography) }
        drawImages(frame, TerminalImageLayer.ABOVE_TEXT, bitmaps, typography)

        if (frame.searchMatches.isNotEmpty()) drawSearchMatches(frame, rows, typography)
        if (frame.selection.isNotEmpty()) drawSelection(frame, rows, typography)

        frame.scrollbar?.takeIf { scrollbarAlpha > 0f }?.let { drawScrollbar(it, frame, scrollbarAlpha, scrollbarEmphasized) }

        if (preedit.isNotEmpty()) {
            // The cursor would be in the way of what is being composed.
            drawPreedit(frame, typography, preedit)
        } else if (cursorShown.value) {
            drawCursor(frame, typography, focused, rows, textShown.value)
        }
        // On top of the cursor, and whether it is shown or not: a program that hides its cursor rings the bell too.
        drawBellPulse(frame, typography, bellPulse())
    }
}

/**
 * The ring that the bell draws around the cursor: a rectangle that starts just outside its cell (at [progress] 1, so that it is seen from the first
 * frame) and grows outward as it fades, until it is gone (at 0). All in pixels: where it is (the stroke is centered on its edge), how thick, and
 * how opaque.
 *
 * @param left Where the cursor's cell is, and how wide: two cells for a wide character.
 * @return `null` when the pulse is over.
 */
internal fun bellRing(progress: Float, left: Float, top: Float, width: Float, cellHeight: Float): BellRing? {
    if (progress <= 0f) return null
    val shown = progress.coerceAtMost(1f)
    val grown = (BELL_PULSE_START + (1f - BELL_PULSE_START) * (1f - shown)) * cellHeight * BELL_PULSE_GROWTH
    val stroke = max(1f, round(cellHeight / 12f))
    return BellRing(
        left = left - grown, top = top - grown, width = width + 2 * grown, height = cellHeight + 2 * grown,
        // The corners start nearly square, as the cursor's, and round as it grows.
        radius = stroke + grown * 0.75f, stroke = stroke, alpha = shown * BELL_PULSE_ALPHA,
    )
}

internal data class BellRing(val left: Float, val top: Float, val width: Float, val height: Float, val radius: Float, val stroke: Float, val alpha: Float)

// How opaque the ring is when the bell rings, how far it grows, in cell heights, and how far outside the cell it starts, as a part of that.
private const val BELL_PULSE_ALPHA = 0.7f
private const val BELL_PULSE_GROWTH = 1f
private const val BELL_PULSE_START = 0.15f

private fun DrawScope.drawBellPulse(frame: TerminalFrame, typography: TerminalTypography, progress: Float) {
    if (progress <= 0f) return
    // Where the cursor is, shown or not; none when it is out of the screen.
    val col = frame.cursor.x ?: return
    val y = frame.cursor.y ?: return
    if (y !in frame.rows.indices) return
    val cols = if (frame.rows[y].cells.getOrNull(col)?.width == TerminalCellWidth.WIDE) 2 else 1
    val left = round(col * typography.cellWidth)
    val width = round((col + cols) * typography.cellWidth) - left
    val ring = bellRing(progress, left, y * typography.cellHeight, width, typography.cellHeight) ?: return
    // The stroke is inside the rectangle: its outer edge is the cursor cell's, then the ring's.
    val half = ring.stroke / 2
    drawRoundRect(
        (frame.cursorColor ?: frame.foreground).rgb.toColor().copy(alpha = ring.alpha),
        Offset(ring.left + half, ring.top + half),
        Size(ring.width - ring.stroke, ring.height - ring.stroke),
        cornerRadius = CornerRadius(ring.radius),
        style = Stroke(width = ring.stroke),
    )
}

/**
 * Whether the cursor is shown: always, unless it blinks. It blinks only in a focused terminal, and starts
 * over, with the cursor shown, whenever it moves.
 */
@Composable
private fun rememberCursorBlink(cursor: TerminalCursor, focused: Boolean): State<Boolean> {
    val shown = remember { mutableStateOf(true) }
    LaunchedEffect(cursor, focused) {
        shown.value = true
        if (cursor.blinking && focused) {
            while (true) {
                delay(CURSOR_BLINK_MS)
                shown.value = !shown.value
            }
        }
    }
    return shown
}

/**
 * Whether the text that blinks (SGR 5) is shown. It is always, and it takes no time, unless the frame has some: then it is the cursor's
 * rhythm, in every terminal alike, whether or not it is focused.
 */
@Composable
private fun rememberTextBlink(frame: TerminalFrame): State<Boolean> {
    val shown = remember { mutableStateOf(true) }
    val blinking = remember(frame) { frame.rows.any { row -> row.cells.any { it.style.blink } } }
    LaunchedEffect(blinking) {
        shown.value = true
        if (blinking) {
            while (true) {
                delay(CURSOR_BLINK_MS)
                shown.value = !shown.value
            }
        }
    }
    return shown
}

// The edges of what is filled are on whole pixels, or the cells would be separated by thin lines of
// the background that show through where two neighbors are blended.
private fun DrawScope.drawCells(startCol: Int, cols: Int, top: Float, typography: TerminalTypography, color: Color) {
    val left = round(startCol * typography.cellWidth)
    val right = round((startCol + cols) * typography.cellWidth)
    drawRect(color, topLeft = Offset(left, top), size = Size(right - left, typography.cellHeight))
}

// The link that a click would open, underlined along the bottom of its cells, in the color of the text: what is written in it is
// underlined or not, as the program wrote it. One that wraps is underlined on each row that it is on.
private fun DrawScope.drawLinkUnderline(link: TerminalLink, color: Color, typography: TerminalTypography) {
    for (segment in link.segments) {
        val left = round(segment.columns.first * typography.cellWidth)
        val right = round((segment.columns.last + 1) * typography.cellWidth)
        val bottom = (segment.row + 1) * typography.cellHeight
        drawRect(color, topLeft = Offset(left, round(bottom) - LINK_UNDERLINE_PX), size = Size(right - left, LINK_UNDERLINE_PX))
    }
}

private const val LINK_UNDERLINE_PX = 1f

/**
 * The images of a layer, on the cells that they cover. They are **clipped to the terminal**: one that is partly scrolled out
 * at the top must not be drawn over what is above it.
 *
 * The edges are on whole pixels, as the cells' are, or the image would shimmer as the window is resized.
 */
private fun DrawScope.drawImages(frame: TerminalFrame, layer: TerminalImageLayer, bitmaps: ImageBitmapCache, typography: TerminalTypography) {
    if (frame.images.none { it.layer == layer }) return
    clipRect(0f, 0f, size.width, size.height) {
        for (placement in frame.images) {
            if (placement.layer != layer) continue
            val image = placement.image
            val left = round(placement.left * typography.cellWidth)
            val top = round(placement.top * typography.cellHeight)
            val right = round((placement.left + placement.width) * typography.cellWidth)
            val bottom = round((placement.top + placement.height) * typography.cellHeight)
            if (right <= left || bottom <= top) continue
            val sourceWidth = if (placement.sourceWidth > 0) placement.sourceWidth else image.width
            val sourceHeight = if (placement.sourceHeight > 0) placement.sourceHeight else image.height
            drawImage(
                image = bitmaps.bitmapFor(image),
                srcOffset = IntOffset(placement.sourceX, placement.sourceY),
                srcSize = IntSize(sourceWidth, sourceHeight),
                dstOffset = IntOffset(left.toInt(), top.toInt()),
                dstSize = IntSize((right - left).toInt(), (bottom - top).toInt()),
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}

/** The width of the strip along the right edge where the scrollbar is, and what the pointer in it is for. */
internal val SCROLLBAR_STRIP_WIDTH = 12.dp
private val SCROLLBAR_THUMB_WIDTH = 6.dp
private val SCROLLBAR_THUMB_MARGIN = 3.dp
internal val SCROLLBAR_MIN_THUMB_HEIGHT = 24.dp

/**
 * The scrollbar, over the text: a thumb in the color of the text, faint, and stronger when it is used.
 */
private fun DrawScope.drawScrollbar(scrollbar: TerminalScrollbar, frame: TerminalFrame, alpha: Float, emphasized: Boolean) {
    val geometry = ScrollbarGeometry(size.height, scrollbar, SCROLLBAR_MIN_THUMB_HEIGHT.toPx())
    val thumbWidth = SCROLLBAR_THUMB_WIDTH.toPx()
    drawRoundRect(
        color = frame.foreground.rgb.toColor().copy(alpha = alpha * (if (emphasized) 0.6f else 0.35f)),
        topLeft = Offset(size.width - thumbWidth - SCROLLBAR_THUMB_MARGIN.toPx(), geometry.thumbTop),
        size = Size(thumbWidth, geometry.thumbHeight),
        cornerRadius = CornerRadius(thumbWidth / 2),
    )
}

/**
 * What is selected: its cells are filled with the selection color, and the text that is in them is drawn again, in
 * the color of selected text. Without a theme that has them, it is the colors of the terminal the other way round.
 */
private fun DrawScope.drawSelection(frame: TerminalFrame, rows: List<PreparedRow>, typography: TerminalTypography) {
    val background = (frame.selectionColor ?: frame.foreground).rgb.toColor()
    // The text keeps its own color on a selection color of the theme, unless the theme says how selected text is drawn.
    val foreground = (frame.selectedTextColor ?: if (frame.selectionColor == null) frame.background else null)?.rgb?.toColor()
    for ((y, range) in frame.selection.withIndex()) {
        if (range == null || y !in rows.indices) continue
        val top = y * typography.cellHeight
        val left = round(range.first * typography.cellWidth)
        val right = round((range.last + 1) * typography.cellWidth)
        clipRect(left, top, right, top + typography.cellHeight) {
            drawRect(background, Offset(left, top), Size(right - left, typography.cellHeight))
            for (text in rows[y].texts) drawPreparedText(text, top, typography, foreground)
            for (sprite in rows[y].sprites) drawPreparedSprite(sprite, top, typography.cellHeight, foreground)
        }
    }
}

/**
 * The matches of a search: the cells are filled, and the text that is in them is drawn again over it. They are the same on every theme:
 * a yellow that every one has, and an orange for the one that is selected.
 */
private fun DrawScope.drawSearchMatches(frame: TerminalFrame, rows: List<PreparedRow>, typography: TerminalTypography) {
    // The selected one last, which is on top if two meet.
    for (match in frame.searchMatches.sortedBy { it.current }) {
        if (match.row !in rows.indices) continue
        val top = match.row * typography.cellHeight
        val left = round(match.columns.first * typography.cellWidth)
        val right = round((match.columns.last + 1) * typography.cellWidth)
        clipRect(left, top, right, top + typography.cellHeight) {
            drawRect(if (match.current) SEARCH_CURRENT_BACKGROUND else SEARCH_MATCH_BACKGROUND, Offset(left, top), Size(right - left, typography.cellHeight))
            for (text in rows[match.row].texts) drawPreparedText(text, top, typography, SEARCH_TEXT)
            for (sprite in rows[match.row].sprites) drawPreparedSprite(sprite, top, typography.cellHeight, SEARCH_TEXT)
        }
    }
}

private val SEARCH_MATCH_BACKGROUND = Color(0xFFFFD866)
private val SEARCH_CURRENT_BACKGROUND = Color(0xFFFF9E3D)
private val SEARCH_TEXT = Color(0xFF1E1E1E)

// A character drawn as a shape, in the cell that starts at the top of its row.
private fun DrawScope.drawPreparedSprite(prepared: PreparedSprite, top: Float, cellHeight: Float, colorOverride: Color? = null) {
    val color = colorOverride ?: prepared.foreground.toColor()
    val sprite = prepared.sprite
    for (r in sprite.rects) {
        drawRect(
            color.copy(alpha = color.alpha * r.alpha),
            topLeft = Offset(prepared.left + r.left, top + r.top),
            size = Size(r.right - r.left, r.bottom - r.top),
        )
    }
    if (sprite.polygons.isEmpty() && sprite.strokes.isEmpty()) return
    // The paths are the row's, made once from its top.
    translate(top = top) {
        for (path in prepared.polygonPaths) drawPath(path, color)
        // Clipped to the cell: a line that goes to its corner is not wider than the cell.
        if (prepared.strokePaths.isNotEmpty()) clipRect(prepared.left, 0f, prepared.left + prepared.width, cellHeight) {
            for ((path, width) in prepared.strokePaths) drawPath(path, color, style = Stroke(width = width))
        }
    }
}

private fun DrawScope.drawPreparedText(text: PreparedText, top: Float, typography: TerminalTypography, colorOverride: Color? = null) {
    val run = text.run
    val left = run.startCol * typography.cellWidth
    val color = colorOverride ?: run.foreground.toColor()
    // A run of plain text is the font of the terminal, exactly as wide as its cells. What is drawn on its own, in its own cells, may be from
    // another font: it is made to fit them.
    val fit = if (run.text.all { it in ' '..'~' }) {
        GlyphFit.NONE
    } else {
        fitGlyph(text.layout.getLineRight(0) - text.layout.getLineLeft(0), run.cols * typography.cellWidth)
    }
    if (fit == GlyphFit.NONE) {
        drawText(text.layout, color = color, topLeft = Offset(left, top))
    } else {
        withTransform({ scale(fit.scale, fit.scale, pivot = Offset(left, top + typography.cellHeight / 2)) }) {
            drawText(text.layout, color = color, topLeft = Offset(left + fit.offsetX, top))
        }
    }

    val right = (run.startCol + run.cols) * typography.cellWidth
    val thickness = max(1f, round(typography.cellHeight / 16f))
    val baseline = top + text.layout.firstBaseline
    if (run.underline != TerminalUnderline.NONE) {
        // The underline has a color of its own when the program set one (SGR 58), even over a search match.
        drawUnderline(run.underline, left, right, baseline + thickness, thickness, run.underlineColor?.toColor() ?: color)
    }
    if (run.strikethrough) {
        val y = baseline - typography.cellHeight * 0.3f
        drawLine(color, Offset(left, y), Offset(right, y), thickness)
    }
    if (run.overline) {
        drawLine(color, Offset(left, top + thickness / 2), Offset(right, top + thickness / 2), thickness)
    }
}

private fun DrawScope.drawUnderline(kind: TerminalUnderline, left: Float, right: Float, y: Float, thickness: Float, color: Color) {
    when (kind) {
        TerminalUnderline.NONE -> Unit
        TerminalUnderline.SINGLE -> drawLine(color, Offset(left, y), Offset(right, y), thickness)
        TerminalUnderline.DOUBLE -> {
            drawLine(color, Offset(left, y), Offset(right, y), thickness)
            drawLine(color, Offset(left, y + thickness * 2), Offset(right, y + thickness * 2), thickness)
        }
        TerminalUnderline.DOTTED -> drawLine(
            color, Offset(left, y), Offset(right, y), thickness,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(thickness, thickness)),
        )
        TerminalUnderline.DASHED -> drawLine(
            color, Offset(left, y), Offset(right, y), thickness,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(thickness * 4, thickness * 2)),
        )
        TerminalUnderline.CURLY -> {
            // A wave, one period every two thicknesses of height.
            val amplitude = thickness
            val half = thickness * 2
            val path = Path().apply {
                moveTo(left, y)
                var x = left
                var up = true
                while (x < right) {
                    val next = minOf(x + half, right)
                    quadraticTo((x + next) / 2, y + if (up) amplitude else -amplitude, next, y)
                    x = next
                    up = !up
                }
            }
            drawPath(path, color, style = Stroke(width = thickness))
        }
    }
}

/**
 * The text that is being composed, in the cells from the cursor's, underlined: it is not in the terminal yet.
 * It covers what is under it.
 */
private fun DrawScope.drawPreedit(frame: TerminalFrame, typography: TerminalTypography, preedit: String) {
    val cursor = frame.cursor
    val cursorCol = cursor.x ?: return
    val y = cursor.y ?: return
    if (y !in frame.rows.indices) return

    val cells = preeditCells(preedit)
    var col = preeditStart(cursorCol, cells.sumOf { it.cols }, frame.cols)
    val top = y * typography.cellHeight
    val thickness = max(1f, round(typography.cellHeight / 16f))
    val color = frame.foreground.rgb.toColor()
    for (cell in cells) {
        drawCells(col, cell.cols, top, typography, frame.background.rgb.toColor())
        val layout = typography.layout(cell.text, bold = false, italic = false)
        val left = col * typography.cellWidth
        drawText(layout, color = color, topLeft = Offset(left, top))
        val right = (col + cell.cols) * typography.cellWidth
        drawUnderline(TerminalUnderline.SINGLE, left, right, top + layout.firstBaseline + thickness, thickness, color)
        col += cell.cols
    }
}

private fun DrawScope.drawCursor(frame: TerminalFrame, typography: TerminalTypography, focused: Boolean, rows: List<PreparedRow>, textShown: Boolean) {
    val cursor = frame.cursor
    val x = cursor.x ?: return
    val y = cursor.y ?: return
    if (!cursor.visible || y !in frame.rows.indices) return

    // On the right half of a wide character (after a backspace), it is drawn over the whole character, as Ghostty does.
    val onTail = x > 0 && frame.rows[y].cells.getOrNull(x)?.width == TerminalCellWidth.SPACER_TAIL &&
        frame.rows[y].cells.getOrNull(x - 1)?.width == TerminalCellWidth.WIDE
    val col = if (onTail) x - 1 else x
    val cell = frame.rows[y].cells.getOrNull(col)
    val cols = if (cell?.width == TerminalCellWidth.WIDE) 2 else 1
    val color = (frame.cursorColor ?: frame.foreground).rgb.toColor()
    val top = y * typography.cellHeight
    val left = round(col * typography.cellWidth)
    val width = round((col + cols) * typography.cellWidth) - left
    val thickness = max(1f, round(typography.cellHeight / 8f))

    // A block that does not have the focus is an outline: it is not where the keys go.
    val shape = if (cursor.shape == TerminalCursorShape.BLOCK && !focused) TerminalCursorShape.BLOCK_HOLLOW else cursor.shape
    when (shape) {
        TerminalCursorShape.BLOCK -> {
            drawRect(color, Offset(left, top), Size(width, typography.cellHeight))
            // The character under it, in the color that the theme gives it, or else the one of the background: the other way round it would
            // not show. It is the row's own, as the selection draws it (the glyph fitted to its cells, the shape of a box character), cut to
            // the cursor; not one that the program hid, nor one that blinks while it is hidden.
            val row = rows.getOrNull(y)
            if (cell != null && row != null && !cell.style.invisible) {
                val textColor = (frame.cursorTextColor ?: frame.background).rgb.toColor()
                clipRect(left, top, left + width, top + typography.cellHeight) {
                    for (text in row.texts) if (textShown || !text.run.blink) drawPreparedText(text, top, typography, textColor)
                    for (sprite in row.sprites) if (textShown || !sprite.blink) drawPreparedSprite(sprite, top, typography.cellHeight, textColor)
                }
            }
        }
        TerminalCursorShape.BLOCK_HOLLOW -> drawRoundRect(
            color,
            Offset(left + 0.5f, top + 0.5f),
            Size(width - 1f, typography.cellHeight - 1f),
            cornerRadius = CornerRadius.Zero,
            style = Stroke(width = max(1f, round(typography.cellHeight / 16f))),
        )
        TerminalCursorShape.BAR -> drawRect(color, Offset(left, top), Size(thickness, typography.cellHeight))
        TerminalCursorShape.UNDERLINE -> drawRect(
            color,
            Offset(left, top + typography.cellHeight - thickness),
            Size(width, thickness),
        )
    }
}
