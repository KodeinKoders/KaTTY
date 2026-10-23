package app.katty.core

import java.nio.file.Path

// What is shared by the stores that find the conversation of an agent in its own files.

/**
 * How long before the process started a conversation may have been made and still be its own: the program and its files do not have the same
 * clock reading, and an agent makes its conversation as it starts.
 */
internal const val BEFORE_START_MS = 2_000L

/** The real path of [directory], or [directory] itself when it is not there any more. */
internal fun realPath(directory: String): String =
    try {
        Path.of(directory).toRealPath().toString()
    } catch (e: Exception) {
        directory
    }

/**
 * Whether [recorded], the directory that an agent wrote, is [cwd], the one that the system says the process is in: as real paths, since an
 * agent may write it as it was given (`/tmp/x`) and the system says `/private/tmp/x`.
 */
internal fun sameDirectory(recorded: String, cwd: String): Boolean = realPath(recorded) == realPath(cwd)

/** The word after `--resume` in [argv], a command line, or the value of `--resume=`: it may be another option, which the caller checks. */
internal fun resumeValue(argv: List<String>): String? {
    val words = argv.drop(1)
    return words.firstOrNull { it.startsWith("--resume=") }?.substringAfter('=')
        ?: words.indexOf("--resume").takeIf { it >= 0 }?.let { words.getOrNull(it + 1) }
}

/** [millis] since the epoch, in the seconds that the databases of Crush, Goose and Cline count in. */
internal fun epochSeconds(millis: Long): Long = millis / 1000
