package app.katty.terminal

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A run of unicode placeholders of the Kitty graphics protocol: cells of one row that show neighboring parts of one image.
 *
 * @param startColumn The column of the screen where it starts, and [width] cells go on from there.
 * @param col The column of the image that the first cell shows, and [imageRow] its row, in the grid that the image is fitted in.
 */
internal class PlaceholderRun(
    val row: Int,
    val startColumn: Int,
    val width: Int,
    val imageId: Int,
    val placementId: Int,
    val col: Int,
    val imageRow: Int,
)

/** Where a run is drawn, and which part of the image it shows. */
internal class PlaceholderPlacement(
    val offsetXPx: Double,
    val offsetYPx: Double,
    val widthPx: Double,
    val heightPx: Double,
    val sourceX: Int,
    val sourceY: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

/**
 * The runs of placeholders in [rows], as Kitty and Ghostty read them. A run is one row. What a cell leaves out continues the cell before it:
 * its row (it is the same), its column (the next) and the high byte of the image id (the same), and a cell that does not fit what comes before
 * starts a run of its own, in which what is missing is 0.
 */
internal fun placeholderRuns(rows: List<TerminalRow>): List<PlaceholderRun> {
    class Open(
        val startColumn: Int,
        val low: Int,
        val placementId: Int,
        val imageRow: Int,
        val col: Int,
        val high: Int?,
        var width: Int = 1,
    ) {
        fun canAppend(cell: TerminalPlaceholder) =
            cell.imageIdLow == low && cell.placementId == placementId &&
                (cell.row == null || cell.row == imageRow) &&
                (cell.col == null || cell.col == col + width) &&
                (cell.high == null || cell.high == high)

        fun complete(row: Int) = PlaceholderRun(row, startColumn, width, low or ((high ?: 0) shl 24), placementId, col, imageRow)
    }

    val result = ArrayList<PlaceholderRun>()
    for ((y, row) in rows.withIndex()) {
        if (!row.hasPlaceholder) continue
        var open: Open? = null
        for ((x, cell) in row.cells.withIndex()) {
            val placeholder = cell.placeholder
            if (placeholder == null) {
                open?.let { result += it.complete(y) }
                open = null
                continue
            }
            val current = open
            if (current != null && current.canAppend(placeholder)) {
                current.width++
                continue
            }
            current?.let { result += it.complete(y) }
            open = Open(x, placeholder.imageIdLow, placeholder.placementId, placeholder.row ?: 0, placeholder.col ?: 0, placeholder.high)
        }
        open?.let { result += it.complete(y) }
    }
    return result
}

/**
 * Where [run] is drawn and what part of the image it shows: the image is fitted in a grid of [gridColumns] by [gridRows] cells, keeping its
 * ratio, and centered, and a run shows its columns of the grid. The part of the grid that is not the image (what centering leaves) is not
 * drawn: the source and the destination are cut to the image. `null` when nothing of the image is in the run.
 *
 * It is what Ghostty does (`graphics_unicode.zig`), with the same rounding.
 */
internal fun placeholderPlacement(
    run: PlaceholderRun,
    imageWidth: Int,
    imageHeight: Int,
    gridColumns: Int,
    gridRows: Int,
    cellWidth: Int,
    cellHeight: Int,
): PlaceholderPlacement? {
    val imgWidth = imageWidth.toDouble()
    val imgHeight = imageHeight.toDouble()
    val gridWidthPx = (gridColumns * cellWidth).toDouble()
    val gridHeightPx = (gridRows * cellHeight).toDouble()

    // The image fitted in the grid.
    val xScale: Double
    val yScale: Double
    val xOffset: Double
    val yOffset: Double
    if (imgWidth * gridHeightPx > imgHeight * gridWidthPx) {
        xScale = gridWidthPx / max(imgWidth, 1.0)
        yScale = xScale
        xOffset = 0.0
        yOffset = (gridHeightPx - imgHeight * yScale) / 2
    } else {
        yScale = gridHeightPx / max(imgHeight, 1.0)
        xScale = yScale
        xOffset = (gridWidthPx - imgWidth * xScale) / 2
        yOffset = 0.0
    }

    // The image as it is, with the margins that fitting leaves, in the pixels of the image.
    val scaledXOffset = xOffset / xScale
    val scaledYOffset = yOffset / yScale
    val scaledWidth = imgWidth + scaledXOffset * 2
    val scaledHeight = imgHeight + scaledYOffset * 2

    var sourceX = scaledWidth * (run.col.toDouble() / gridColumns)
    var sourceY = scaledHeight * (run.imageRow.toDouble() / gridRows)
    var sourceWidth = scaledWidth * (run.width.toDouble() / gridColumns)
    var sourceHeight = scaledHeight * (1.0 / gridRows)

    var destX = 0.0
    var destY = 0.0
    var destWidth = (run.width * cellWidth).toDouble()
    var destHeight = cellHeight.toDouble()

    if (sourceY < scaledYOffset) {
        // In the top margin: the source starts at the image, and the destination after the margin.
        val offset = scaledYOffset - sourceY
        sourceHeight -= offset
        destY = offset
        destHeight -= offset * yScale
        sourceY = 0.0
        // Both margins are in it.
        if (sourceHeight > imgHeight) {
            sourceHeight = imgHeight
            destHeight = imgHeight * yScale
        }
    } else if (sourceY + sourceHeight > scaledHeight - scaledYOffset) {
        // In the bottom margin: the source is shortened to the image.
        sourceY -= scaledYOffset
        sourceHeight = scaledHeight - scaledYOffset - sourceY
        sourceHeight -= scaledYOffset
        destHeight = sourceHeight * yScale
    } else {
        sourceY -= scaledYOffset
    }

    if (sourceX < scaledXOffset) {
        val offset = scaledXOffset - sourceX
        sourceWidth -= offset
        destX = offset
        destWidth -= offset * xScale
        sourceX = 0.0
        if (sourceWidth > imgWidth) {
            sourceWidth = imgWidth
            destWidth = imgWidth * xScale
        }
    } else if (sourceX + sourceWidth > scaledWidth - scaledXOffset) {
        sourceX -= scaledXOffset
        sourceWidth = scaledWidth - scaledXOffset - sourceX
        sourceWidth -= scaledXOffset
        destWidth = sourceWidth * xScale
    } else {
        sourceX -= scaledXOffset
    }

    // Nothing of the image is in it.
    if (sourceWidth <= 0 || sourceHeight <= 0) return null

    return PlaceholderPlacement(
        offsetXPx = (destX * xScale).roundToInt().toDouble(),
        offsetYPx = (destY * yScale).roundToInt().toDouble(),
        widthPx = destWidth.roundToInt().toDouble(),
        heightPx = destHeight.roundToInt().toDouble(),
        sourceX = sourceX.roundToInt(),
        sourceY = sourceY.roundToInt(),
        sourceWidth = sourceWidth.roundToInt(),
        sourceHeight = sourceHeight.roundToInt(),
    )
}
