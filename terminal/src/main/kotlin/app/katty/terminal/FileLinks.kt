package app.katty.terminal

import java.net.URI
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

// The files and folders that links name: a path written in the text is looked for in the directories that the program could have meant, and a
// `file://` address may have the line after it, as Kotlin's compiler writes it (`file:///…/Foo.kt:12:5`).

// The line and the column at the end of the path of a `file://` address.
private val LINE_SUFFIX = Regex("""^(.+?):(\d{1,9})(?::(\d{1,9}))?$""")

/**
 * [link] with the file or the folder that it names, or as it is when it names none (an address of the web). A path written in the text is
 * absolute, from the home (`~/`), or relative to one of [directories], which are only asked for then, the first one where it is there; when it
 * is nowhere there is no link at all, so that a word that only looks like a path is not underlined. A `file://` address of this machine has it
 * too, with the line and the column that may end the path of a file. A folder has no line.
 */
internal fun fileLink(link: TerminalLink, directories: () -> List<String>): TerminalLink? {
    val written = link.file
    if (written != null) {
        val file = resolveFile(written.path, directories) ?: return null
        return TerminalLink(file.toUri().toString(), link.segments, position(file, written.line, written.column))
    }
    if (!link.url.startsWith("file:", ignoreCase = true)) return link
    val uri = try {
        URI(link.url)
    } catch (_: Exception) {
        return link
    }
    val host = uri.host ?: uri.authority
    if (!host.isNullOrEmpty() && host != "localhost") return link
    val path = uri.path?.takeIf { it.isNotEmpty() } ?: return link
    existing(path)?.let { return link.copy(file = position(it, null, null)) }
    // `file:///a/Foo.kt:12:5`: the file is `/a/Foo.kt`, when that is one and the whole is not.
    val suffix = LINE_SUFFIX.matchEntire(path) ?: return link
    val file = existing(suffix.groupValues[1])?.takeIf { Files.isRegularFile(it) } ?: return link
    val line = suffix.groupValues[2].toInt().takeIf { it > 0 }
    val column = suffix.groups[3]?.value?.toInt()?.takeIf { it > 0 && line != null }
    return TerminalLink(file.toUri().toString(), link.segments, position(file, line, column))
}

/** The file or the folder that [written] names, as a program printed it, or `null` when there is none: see [fileLink]. */
internal fun resolveFile(written: String, directories: () -> List<String>): Path? {
    if (written.startsWith("/")) return existing(written)
    if (written.startsWith("~/")) return System.getProperty("user.home")?.let { existing(it + written.substring(1)) }
    for (directory in directories()) {
        if (!directory.startsWith("/")) continue
        val file = try {
            Path.of(directory).resolve(written).normalize()
        } catch (_: InvalidPathException) {
            continue
        }
        if (Files.isRegularFile(file) || Files.isDirectory(file)) return file
    }
    return null
}

// A folder has no line: `src:12` is the folder.
private fun position(file: Path, line: Int?, column: Int?): TerminalFilePosition {
    val directory = Files.isDirectory(file)
    return if (directory) TerminalFilePosition(file.toString(), directory = true) else TerminalFilePosition(file.toString(), line, column)
}

// A regular file or a folder: not a device, nor a pipe.
private fun existing(path: String): Path? = try {
    Path.of(path).normalize().takeIf { Files.isRegularFile(it) || Files.isDirectory(it) }
} catch (_: InvalidPathException) {
    null
}
