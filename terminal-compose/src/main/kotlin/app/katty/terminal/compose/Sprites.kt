package app.katty.terminal.compose

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The characters that are drawn as shapes that fill their cell, and not with the font: the box drawing, the blocks, the braille patterns and
// the arrows of powerline. A font draws them with the glyphs it has, which stop short of the edge of the cell or go over it, so a line
// has gaps where two cells meet and a block has a seam. Drawn as rectangles on whole pixels they join, whatever the font.
//
// This is pure geometry, in the pixels of one cell with the origin at its top left, so that it can be tested without drawing anything.

/** A filled rectangle, [alpha] of the color of the text over what is there (the shades are 0.25, 0.5 and 0.75). */
internal data class SpriteRect(val left: Float, val top: Float, val right: Float, val bottom: Float, val alpha: Float = 1f)

internal sealed interface PathCommand {
    data class MoveTo(val x: Float, val y: Float) : PathCommand
    data class LineTo(val x: Float, val y: Float) : PathCommand
    data class QuadTo(val controlX: Float, val controlY: Float, val x: Float, val y: Float) : PathCommand
}

/** A line [width] wide along a path. */
internal class SpriteStroke(val commands: List<PathCommand>, val width: Float)

/** What a character is drawn as. */
internal class Sprite(
    val rects: List<SpriteRect> = emptyList(),
    val strokes: List<SpriteStroke> = emptyList(),
    /** Filled polygons, as the points of their corners. */
    val polygons: List<List<Pair<Float, Float>>> = emptyList(),
)

/** Whether [codepoint] is one of the characters that [sprite] draws. */
internal fun isSprite(codepoint: Int): Boolean =
    codepoint in BOX_RANGE || codepoint in BLOCK_RANGE || codepoint in BRAILLE_RANGE || codepoint in POWERLINE_RANGE

private val BOX_RANGE = 0x2500..0x257F
private val BLOCK_RANGE = 0x2580..0x259F
private val BRAILLE_RANGE = 0x2800..0x28FF
private val POWERLINE_RANGE = 0xE0B0..0xE0B3

/**
 * The shape of [codepoint] in a cell of [width] by [height] pixels, `null` for a character that is not drawn this way.
 */
internal fun sprite(codepoint: Int, width: Int, height: Int): Sprite? = when (codepoint) {
    in BOX_RANGE -> boxSprite(codepoint, width.toFloat(), height.toFloat())
    in BLOCK_RANGE -> blockSprite(codepoint, width.toFloat(), height.toFloat())
    in BRAILLE_RANGE -> brailleSprite(codepoint, width.toFloat(), height.toFloat())
    in POWERLINE_RANGE -> powerlineSprite(codepoint, width.toFloat(), height.toFloat())
    else -> null
}

// ---- Blocks ----

private fun blockSprite(codepoint: Int, w: Float, h: Float): Sprite {
    fun rect(left: Float, top: Float, right: Float, bottom: Float, alpha: Float = 1f) = SpriteRect(
        left = (w * left).roundToInt().toFloat(), top = (h * top).roundToInt().toFloat(),
        right = (w * right).roundToInt().toFloat(), bottom = (h * bottom).roundToInt().toFloat(), alpha = alpha,
    )

    val rects = when (codepoint) {
        0x2580 -> listOf(rect(0f, 0f, 1f, 0.5f))
        // Lower one eighth to seven eighths.
        in 0x2581..0x2587 -> listOf(rect(0f, 1f - (codepoint - 0x2580) / 8f, 1f, 1f))
        0x2588 -> listOf(rect(0f, 0f, 1f, 1f))
        // Left seven eighths to one eighth.
        in 0x2589..0x258F -> listOf(rect(0f, 0f, (0x2590 - codepoint) / 8f, 1f))
        0x2590 -> listOf(rect(0.5f, 0f, 1f, 1f))
        0x2591 -> listOf(rect(0f, 0f, 1f, 1f, 0.25f))
        0x2592 -> listOf(rect(0f, 0f, 1f, 1f, 0.5f))
        0x2593 -> listOf(rect(0f, 0f, 1f, 1f, 0.75f))
        0x2594 -> listOf(rect(0f, 0f, 1f, 1 / 8f))
        0x2595 -> listOf(rect(7 / 8f, 0f, 1f, 1f))
        else -> quadrants(codepoint).map { (column, row) -> rect(column / 2f, row / 2f, (column + 1) / 2f, (row + 1) / 2f) }
    }
    return Sprite(rects = rects)
}

// The quadrants of U+2596..U+259F that are filled, as (column, row) of a grid of two by two.
private fun quadrants(codepoint: Int): List<Pair<Int, Int>> {
    val upperLeft = 0 to 0
    val upperRight = 1 to 0
    val lowerLeft = 0 to 1
    val lowerRight = 1 to 1
    return when (codepoint) {
        0x2596 -> listOf(lowerLeft)
        0x2597 -> listOf(lowerRight)
        0x2598 -> listOf(upperLeft)
        0x2599 -> listOf(upperLeft, lowerLeft, lowerRight)
        0x259A -> listOf(upperLeft, lowerRight)
        0x259B -> listOf(upperLeft, upperRight, lowerLeft)
        0x259C -> listOf(upperLeft, upperRight, lowerRight)
        0x259D -> listOf(upperRight)
        0x259E -> listOf(upperRight, lowerLeft)
        else -> listOf(upperRight, lowerLeft, lowerRight)
    }
}

// ---- Braille ----

// The dots of a braille pattern: a grid of two columns by four rows, in the order that the bits of the code point name them.
private val BRAILLE_DOTS = listOf(0 to 0, 0 to 1, 0 to 2, 1 to 0, 1 to 1, 1 to 2, 0 to 3, 1 to 3)

private fun brailleSprite(codepoint: Int, w: Float, h: Float): Sprite {
    val pattern = codepoint - 0x2800
    val dotWidth = max(1f, (w / 4).roundToInt().toFloat())
    val dotHeight = max(1f, (h / 8).roundToInt().toFloat())
    val rects = BRAILLE_DOTS.withIndex().filter { (bit, _) -> pattern and (1 shl bit) != 0 }.map { (_, dot) ->
        val (column, row) = dot
        // The dot is in the middle of its quarter of the width and its eighth of... a quarter of the height.
        val left = floor(w * (column * 2 + 1) / 4 - dotWidth / 2)
        val top = floor(h * (row * 2 + 1) / 8 - dotHeight / 2)
        SpriteRect(left, top, left + dotWidth, top + dotHeight)
    }
    return Sprite(rects = rects)
}

// ---- Powerline ----

private fun powerlineSprite(codepoint: Int, w: Float, h: Float): Sprite {
    val thickness = lightThickness(w, h)
    return when (codepoint) {
        // The triangle that points right, and the one that points left.
        0xE0B0 -> Sprite(polygons = listOf(listOf(0f to 0f, w to h / 2, 0f to h)))
        0xE0B2 -> Sprite(polygons = listOf(listOf(w to 0f, 0f to h / 2, w to h)))
        // The same as a line: the chevrons.
        0xE0B1 -> Sprite(strokes = listOf(SpriteStroke(listOf(PathCommand.MoveTo(0f, 0f), PathCommand.LineTo(w, h / 2), PathCommand.LineTo(0f, h)), thickness)))
        else -> Sprite(strokes = listOf(SpriteStroke(listOf(PathCommand.MoveTo(w, 0f), PathCommand.LineTo(0f, h / 2), PathCommand.LineTo(w, h)), thickness)))
    }
}

// ---- Box drawing ----

private const val NONE = 0
private const val LIGHT = 1
private const val HEAVY = 2
private const val DOUBLE = 3

private const val UP = 0
private const val RIGHT = 1
private const val DOWN = 2
private const val LEFT = 3

/** The thickness of a light line: an eighth of the width of the cell, at least a pixel, on whole pixels. */
internal fun lightThickness(w: Float, h: Float): Float = max(1f, (min(w, h / 2) / 8).roundToInt().toFloat())

// Each of U+2500..U+257F as the style (NONE, LIGHT, HEAVY, DOUBLE) of its arms, up, right, down, left, in four digits. A code point that is
// not here is one that is drawn some other way: the dashes, the arcs and the diagonals.
private val ARMS: Map<Int, String> = buildMap {
    val table = listOf(
        "0101", "0202", "1010", "2020", // 2500..2503
        null, null, null, null, null, null, null, null, // 2504..250B: dashes
        "0110", "0210", "0120", "0220", // 250C..250F
        "0011", "0012", "0021", "0022", // 2510..2513
        "1100", "1200", "2100", "2200", // 2514..2517
        "1001", "1002", "2001", "2002", // 2518..251B
        "1110", "1210", "2110", "1120", "2120", "2210", "1220", "2220", // 251C..2523
        "1011", "1012", "2011", "1021", "2021", "2012", "1022", "2022", // 2524..252B
        "0111", "0112", "0211", "0212", "0121", "0122", "0221", "0222", // 252C..2533
        "1101", "1102", "1201", "1202", "2101", "2102", "2201", "2202", // 2534..253B
        "1111", "1112", "1211", "1212", "2111", "1121", "2121", "2112", // 253C..2543
        "2211", "1122", "1221", "2212", "1222", "2122", "2221", "2222", // 2544..254B
        null, null, null, null, // 254C..254F: dashes
        "0303", "3030", "0310", "0130", "0330", "0013", "0031", "0033", // 2550..2557
        "1300", "3100", "3300", "1003", "3001", "3003", "1310", "3130", // 2558..255F
        "3330", "1013", "3031", "3033", "0313", "0131", "0333", "1303", // 2560..2567
        "3101", "3303", "1313", "3131", "3333", // 2568..256C
        null, null, null, null, // 256D..2570: arcs
        null, null, null, // 2571..2573: diagonals
        "0001", "1000", "0100", "0010", "0002", "2000", "0200", "0020", // 2574..257B
        "0201", "1020", "0102", "2010", // 257C..257F
    )
    table.forEachIndexed { index, arms -> if (arms != null) put(0x2500 + index, arms) }
}

// The dashed lines: how many dashes, and whether it is heavy and whether it is vertical.
private class Dashes(val count: Int, val heavy: Boolean, val vertical: Boolean)

private fun dashes(codepoint: Int): Dashes? = when (codepoint) {
    0x2504 -> Dashes(3, false, false)
    0x2505 -> Dashes(3, true, false)
    0x2506 -> Dashes(3, false, true)
    0x2507 -> Dashes(3, true, true)
    0x2508 -> Dashes(4, false, false)
    0x2509 -> Dashes(4, true, false)
    0x250A -> Dashes(4, false, true)
    0x250B -> Dashes(4, true, true)
    0x254C -> Dashes(2, false, false)
    0x254D -> Dashes(2, true, false)
    0x254E -> Dashes(2, false, true)
    0x254F -> Dashes(2, true, true)
    else -> null
}

private fun boxSprite(codepoint: Int, w: Float, h: Float): Sprite? {
    val t = lightThickness(w, h)
    ARMS[codepoint]?.let { arms -> return Sprite(rects = armRects(IntArray(4) { arms[it] - '0' }, w, h, t)) }
    dashes(codepoint)?.let { return dashedSprite(it, w, h, t) }
    return when (codepoint) {
        0x256D, 0x256E, 0x256F, 0x2570 -> arcSprite(codepoint, w, h, t)
        0x2571 -> Sprite(strokes = listOf(SpriteStroke(listOf(PathCommand.MoveTo(w, 0f), PathCommand.LineTo(0f, h)), t)))
        0x2572 -> Sprite(strokes = listOf(SpriteStroke(listOf(PathCommand.MoveTo(0f, 0f), PathCommand.LineTo(w, h)), t)))
        0x2573 -> Sprite(
            strokes = listOf(
                SpriteStroke(listOf(PathCommand.MoveTo(w, 0f), PathCommand.LineTo(0f, h)), t),
                SpriteStroke(listOf(PathCommand.MoveTo(0f, 0f), PathCommand.LineTo(w, h)), t),
            ),
        )
        else -> null
    }
}

// The width that an arm of a style takes across: a double one is two lines with a line's gap between them.
private fun span(style: Int, t: Float): Float = when (style) {
    NONE -> 0f
    LIGHT -> t
    HEAVY -> t * 2
    else -> t * 3
}

// Where a band of [size] pixels starts in [length], centered on whole pixels.
private fun centered(length: Float, size: Float): Float = floor((length - size) / 2)

/**
 * The rectangles of a character made of arms, which go from the edge of the cell to its middle: [arms] has the style of up, right, down and left.
 *
 * Each arm is made in a frame where it comes from the start of its axis towards the middle, and mapped back, so that the four are one piece of code.
 * Where an arm meets the others it goes as far as the far edge of what crosses it, so that a corner is filled; a double arm
 * is two lines, and the one that is on the outside of a corner goes to the far line of the other, and the one inside stops at the near
 * one, which leaves the inner corner of `╔` hollow.
 */
private fun armRects(arms: IntArray, w: Float, h: Float, t: Float): List<SpriteRect> {
    val rects = ArrayList<SpriteRect>()
    for (direction in 0..3) {
        val style = arms[direction]
        if (style == NONE) continue
        val horizontal = direction == LEFT || direction == RIGHT
        // The frame: along the arm's axis from its edge, across it.
        val along = if (horizontal) w else h
        val across = if (horizontal) h else w
        // What crosses it: the arms of the other axis, on the side of smaller and of larger coordinates across.
        val before = arms[if (horizontal) UP else LEFT]
        val after = arms[if (horizontal) DOWN else RIGHT]
        val crossWidth = max(span(before, t), span(after, t))
        // Where what crosses it is along the axis, from the start of the cell: the arms that come from the far end see it mirrored, and the
        // centering of a band does not mirror in a width of another parity, so it is placed once and measured from either end.
        val bandStart = centered(along, crossWidth)
        val fromStart = direction == LEFT || direction == UP
        // How far from its own edge an arm goes to reach [position], which is measured from the start of the cell.
        fun reach(position: Float) = if (fromStart) position else along - position
        // Nothing crosses it: it goes to the middle, rounded up so that the two halves of a line overlap, not leave a pixel, in an odd width.
        val middle = ceil(along / 2)
        val local = ArrayList<SpriteRect>()

        if (style == DOUBLE) {
            val lineStart = centered(across, t * 3)
            // The two lines: the one at smaller coordinates across, and the one at larger.
            for (side in 0..1) {
                val crossed = if (side == 0) before else after
                val opposite = if (side == 0) after else before
                // The line of the arm that has a crossing on its own side is the inner one of a corner: it stops at the near line of a double
                // crossing, which leaves the inner corner of `╔` hollow, or at the far edge of a single one. With a crossing on the other
                // side only it is the outer one, and goes to the far line of it.
                val end = when {
                    crossed == DOUBLE -> reach(if (fromStart) bandStart + t else bandStart + t * 2)
                    crossed != NONE -> reach(if (fromStart) bandStart + span(crossed, t) else bandStart)
                    opposite == DOUBLE -> reach(if (fromStart) bandStart + t * 3 else bandStart)
                    opposite != NONE -> reach(if (fromStart) bandStart + span(opposite, t) else bandStart)
                    else -> middle
                }
                val from = lineStart + side * t * 2
                local += SpriteRect(0f, from, end, from + t)
            }
        } else {
            val thickness = span(style, t)
            val from = centered(across, thickness)
            val end = if (crossWidth == 0f) middle else reach(if (fromStart) bandStart + crossWidth else bandStart)
            local += SpriteRect(0f, from, end, from + thickness)
        }

        // Back from the frame to the cell.
        for (r in local) {
            rects += when (direction) {
                LEFT -> SpriteRect(r.left, r.top, r.right, r.bottom)
                RIGHT -> SpriteRect(w - r.right, r.top, w - r.left, r.bottom)
                UP -> SpriteRect(r.top, r.left, r.bottom, r.right)
                else -> SpriteRect(r.top, h - r.right, r.bottom, h - r.left)
            }
        }
    }
    return rects
}

// A line of dashes along the middle of the cell, with half a gap at each end so that the dashes of two cells side by side are one pattern.
private fun dashedSprite(dashes: Dashes, w: Float, h: Float, t: Float): Sprite {
    val thickness = if (dashes.heavy) t * 2 else t
    val along = if (dashes.vertical) h else w
    val across = if (dashes.vertical) w else h
    val from = centered(across, thickness)
    val pitch = along / dashes.count
    val dash = max(1f, (pitch * 0.6f).roundToInt().toFloat())
    val rects = (0 until dashes.count).map { index ->
        val start = (pitch * index + (pitch - dash) / 2).roundToInt().toFloat()
        if (dashes.vertical) SpriteRect(from, start, from + thickness, start + dash) else SpriteRect(start, from, start + dash, from + thickness)
    }
    return Sprite(rects = rects)
}

// The corner that is rounded: a line from the middle of one edge to the middle of the other, bending in the middle of the cell.
private fun arcSprite(codepoint: Int, w: Float, h: Float, t: Float): Sprite {
    val x = centered(w, t) + t / 2
    val y = centered(h, t) + t / 2
    val radius = min(w, h) / 2
    val commands = when (codepoint) {
        // Down and right, down and left, up and left, up and right.
        0x256D -> listOf(PathCommand.MoveTo(x, h), PathCommand.LineTo(x, y + radius), PathCommand.QuadTo(x, y, x + radius, y), PathCommand.LineTo(w, y))
        0x256E -> listOf(PathCommand.MoveTo(x, h), PathCommand.LineTo(x, y + radius), PathCommand.QuadTo(x, y, x - radius, y), PathCommand.LineTo(0f, y))
        0x256F -> listOf(PathCommand.MoveTo(x, 0f), PathCommand.LineTo(x, y - radius), PathCommand.QuadTo(x, y, x - radius, y), PathCommand.LineTo(0f, y))
        else -> listOf(PathCommand.MoveTo(x, 0f), PathCommand.LineTo(x, y - radius), PathCommand.QuadTo(x, y, x + radius, y), PathCommand.LineTo(w, y))
    }
    return Sprite(strokes = listOf(SpriteStroke(commands, t)))
}
