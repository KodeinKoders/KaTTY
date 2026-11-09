package app.katty.terminal.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.io.IOException

// What a shell would take for something else than a character of the path: a backslash goes before each, as Ghostty does it on macOS.
private const val SHELL_SPECIAL = "\\ ()[]{}<>\"'`!#$&;|*?\t"

/**
 * [path] for a shell's command line: a backslash before each character that a shell would take for something else (a space, a quote, `$`, a
 * bracket…), which zsh, bash and fish all read the same, whichever of them runs. It is what Ghostty, iTerm2 and Terminal.app write for a file
 * that is dropped on them, so a program in the terminal that recognizes such a path (an agent, for an image) gets what it knows.
 */
internal fun shellEscaped(path: String): String = buildString(path.length) {
    for (c in path) {
        if (c in SHELL_SPECIAL) append('\\')
        append(c)
    }
}

/** Whether [this] is something that [droppedText] makes a text of: files, or text. */
internal fun Transferable.isDroppable(): Boolean =
    isDataFlavorSupported(DataFlavor.javaFileListFlavor) || isDataFlavorSupported(DataFlavor.stringFlavor)

/**
 * What a drop of [this] on a terminal pastes: the paths of files ([shellEscaped], separated by a space, as Ghostty does), or a text as it is;
 * `null` for anything else (an image that is no file), or when it cannot be read.
 *
 * A path with a control character (a line break in a file's name) is left out: a backslash cannot write it safely, and it would end the line.
 */
internal fun Transferable.droppedText(): String? = try {
    when {
        isDataFlavorSupported(DataFlavor.javaFileListFlavor) -> {
            val files = (getTransferData(DataFlavor.javaFileListFlavor) as? List<*>).orEmpty().filterIsInstance<File>()
            files.map { it.absolutePath }.filter { path -> path.none { it.isISOControl() } }.takeIf { it.isNotEmpty() }
                ?.joinToString(" ", transform = ::shellEscaped)
        }
        isDataFlavorSupported(DataFlavor.stringFlavor) -> (getTransferData(DataFlavor.stringFlavor) as? String)?.takeIf { it.isNotEmpty() }
        else -> null
    }
} catch (_: IOException) {
    null
} catch (_: UnsupportedFlavorException) {
    null
}

/**
 * Pastes what a drop of [transferable] gives ([droppedText]) through [paste], and says whether there was something: a drop that gives nothing is
 * refused.
 */
internal fun drop(transferable: Transferable, paste: (String) -> Unit): Boolean {
    val text = transferable.droppedText() ?: return false
    paste(text)
    return true
}

/** What a terminal shows of a drag from another application, as [DropFeedback.next] follows it. */
internal enum class DropFeedback {
    /** Nothing is dragged that it would take. */
    NONE,

    /** Something that it would take is dragged over the window, elsewhere: it says that it takes drops. */
    AVAILABLE,

    /** It is under the pointer: what is dropped now comes to it. */
    TARGET,
    ;

    /** What it shows after [event]. A drag that ends, dropped or not, ends it everywhere. */
    fun next(event: DropEvent): DropFeedback = when (event) {
        DropEvent.STARTED -> if (this == TARGET) TARGET else AVAILABLE
        DropEvent.ENTERED -> TARGET
        DropEvent.EXITED -> if (this == NONE) NONE else AVAILABLE
        DropEvent.ENDED, DropEvent.DROPPED -> NONE
    }
}

/** What Compose tells a drop target of a drag: it comes over the window, comes over the target, leaves it, is dropped, ends. */
internal enum class DropEvent { STARTED, ENTERED, EXITED, DROPPED, ENDED }

/**
 * What the terminal under the pointer says it will do with what is dragged: paste the paths of files (their number, when the system says it
 * while dragging), or a text. Reading the list during the drag may not be allowed: then it says it without the number.
 */
internal fun Transferable.dropLabel(): String {
    if (!isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return "Drop to paste the text"
    val count = try {
        (getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)?.size
    } catch (_: Exception) {
        null
    }
    return when (count) {
        1 -> "Drop to paste the path of the file"
        null -> "Drop to paste the paths of the files"
        else -> "Drop to paste the paths of the $count files"
    }
}

// The lines that show what a drag would do, and the label of the terminal under the pointer.
private val DROP_LINE = 2.dp
private val DROP_DASH = 6.dp
private val DROP_GAP = 4.dp
private val LABEL_PADDING_X = 12.dp
private val LABEL_PADDING_Y = 6.dp
private val LABEL_RADIUS = 6.dp
private const val TARGET_TINT = 0.12f

/** How the label of the terminal under the pointer is written. */
internal val DROP_LABEL_STYLE = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)

/**
 * What a terminal shows of a drag ([feedback]), over what it modifies, in the terminal's own colors ([foreground] over [background]):
 * - while something it takes is dragged over the window, a dashed line along its edges, so that it is seen that a drop is taken;
 * - while it is under the pointer, a tint of the foreground over all of it, a solid line, and [label] in the middle (the background's color on
 *   the foreground's), so that it is seen which terminal the drop goes to, and what it will do. The label is measured for the terminal's width:
 *   in a narrow one it goes on onto a second line, then is cut.
 */
internal fun Modifier.dropFeedback(
    feedback: DropFeedback,
    foreground: Color,
    background: Color,
    label: String,
    measurer: TextMeasurer,
    style: TextStyle,
): Modifier = drawWithContent {
    drawContent()
    if (feedback == DropFeedback.NONE) return@drawWithContent
    val line = DROP_LINE.toPx()
    // A stroke is centered on its path: the path is half a line inside, so that all of it is.
    val inset = Offset(line / 2, line / 2)
    val box = Size(size.width - line, size.height - line)
    if (feedback == DropFeedback.AVAILABLE) {
        val dashes = PathEffect.dashPathEffect(floatArrayOf(DROP_DASH.toPx(), DROP_GAP.toPx()))
        drawRect(foreground.copy(alpha = 0.6f), topLeft = inset, size = box, style = Stroke(line, pathEffect = dashes))
        return@drawWithContent
    }
    drawRect(foreground.copy(alpha = TARGET_TINT))
    drawRect(foreground.copy(alpha = 0.8f), topLeft = inset, size = box, style = Stroke(line))
    val padX = LABEL_PADDING_X.toPx()
    val padY = LABEL_PADDING_Y.toPx()
    val room = (size.width - 2 * (padX + line)).toInt()
    if (label.isEmpty() || room <= 0) return@drawWithContent
    // The measurer keeps what it measured: the same label in the same room is not laid out again at each frame.
    val text = measurer.measure(label, style, overflow = TextOverflow.Ellipsis, maxLines = 2, constraints = Constraints(maxWidth = room))
    val width = text.size.width + 2 * padX
    val height = text.size.height + 2 * padY
    // A terminal that is not as high as the label still has its tint and its line.
    if (height > size.height) return@drawWithContent
    val topLeft = Offset((size.width - width) / 2, (size.height - height) / 2)
    drawRoundRect(foreground, topLeft = topLeft, size = Size(width, height), cornerRadius = CornerRadius(LABEL_RADIUS.toPx()))
    drawText(text, color = background, topLeft = topLeft + Offset(padX, padY))
}
