package app.katty.terminals

import app.katty.terminal.TerminalColor
import app.katty.terminal.TerminalHistory
import app.katty.terminal.TerminalTextFormat
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** How the history of a terminal is exported: what the dialog says of it, the extension of its file, and the text that the terminal gives. */
enum class ExportFormat(val label: String, val description: String, val extension: String, val text: TerminalTextFormat) {
    TEXT("Text", "The characters only", "txt", TerminalTextFormat.PLAIN),
    HTML("HTML", "With the colors and styles, for a browser", "html", TerminalTextFormat.HTML),
    ANSI("ANSI", "With the escape sequences, that cat or less -R show in color", "ans", TerminalTextFormat.VT),
}

// Dates are written in ISO order whatever the language; a colon is no character of a file name on macOS (the Finder shows it as a slash).
private val FILE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH-mm")

/** The name that is proposed for the export of the terminal [title]: `api build 2026-11-10 19-43.html`. What macOS refuses in a name is a dash. */
fun exportFileName(title: String, now: LocalDateTime, format: ExportFormat): String {
    val name = title.replace(Regex("[/:\\p{Cntrl}]"), "-").trim().trimStart('.').ifEmpty { "Terminal" }
    return "$name ${FILE_TIME.format(now)}.${format.extension}"
}

/**
 * What is written to the file of an export of [history] in [format]: the text, ended by a newline as its other lines are (what `cat` shows then
 * leaves the prompt on a row of its own; libghostty ends the lines of VT with a carriage return too), or an HTML document around the fragment of
 * libghostty.
 */
fun exportContent(history: TerminalHistory, format: ExportFormat, title: String): String = when (format) {
    ExportFormat.HTML -> htmlDocument(history.text, title, history.foreground, history.background)
    ExportFormat.TEXT -> history.text.endingWith("\n")
    ExportFormat.ANSI -> history.text.endingWith("\r\n")
}

private fun String.endingWith(newline: String): String = if (isEmpty() || endsWith("\n")) this else this + newline

/**
 * A page that shows [fragment] (the HTML of libghostty, which defines the colors of the palette and draws in monospace) on the colors of the
 * terminal, [foreground] on [background], under the [title]. The font is the system's monospace one rather than the generic `monospace` that
 * libghostty gives its block, which is Courier in Safari, and a link keeps the color of its text.
 */
fun htmlDocument(fragment: String, title: String, foreground: TerminalColor?, background: TerminalColor?): String {
    val colors = listOfNotNull(background?.let { "background: $it;" }, foreground?.let { "color: $it;" }).joinToString(" ")
    // Not a template with a margin: what the terminal shows may have lines that start with one.
    return buildString {
        append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n")
        append("<title>").append(escapeHtml(title)).append("</title>\n")
        append("<style>\nbody { margin: 0; padding: 16px; ").append(colors).append(" }\n")
        append("body > div { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace !important; font-size: 13px; line-height: 1.4; }\n")
        // A link has the color that the program gave its text, as in the terminal, and not the browser's blue.
        append("a { color: inherit; }\n")
        append("</style>\n")
        append("</head>\n<body>\n").append(fragment).append("\n</body>\n</html>\n")
    }
}

private fun escapeHtml(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

/**
 * Why an export failed, in words: the message of a missing folder is the path of the file that was being made, which is not the one the user
 * chose.
 */
fun exportFailureReason(failure: Throwable): String = when (failure) {
    is java.nio.file.NoSuchFileException -> "its folder is not there"
    is java.nio.file.AccessDeniedException -> "permission denied"
    is java.nio.file.FileSystemException -> failure.reason ?: failure.javaClass.simpleName
    else -> failure.message ?: failure.javaClass.simpleName
}

/**
 * Writes [text] to [path], in UTF-8: to another file of the same folder first, which is then moved in its place, so that an export that fails
 * leaves no file half written, and a file that was there is replaced whole.
 */
fun writeExport(path: Path, text: String) {
    val folder = path.toAbsolutePath().parent
    val temporary = Files.createTempFile(folder, ".${path.fileName}", ".part")
    try {
        Files.writeString(temporary, text)
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        Files.deleteIfExists(temporary)
    }
}
