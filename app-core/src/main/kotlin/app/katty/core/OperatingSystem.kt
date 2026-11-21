package app.katty.core

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

/** Whether the application runs on Windows: what differs there only by a value (a path, a library's name) is a condition on it. */
public val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

/** Whether the application runs on macOS. */
public val isMacOs: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac")

/** The name of the system, as a question to an AI says where a command runs. */
public val systemName: String = when {
    isMacOs -> "macOS"
    isWindows -> "Windows"
    else -> System.getProperty("os.name").orEmpty().ifEmpty { "Linux" }
}

/**
 * The program [name] in the folders of [searchPath] (separated as a `PATH` is), the first one that has it, as the shell finds it; `null` when
 * none does. On Windows a name without an extension is looked for with each of `PATHEXT` (`.EXE`, `.CMD`…), and a file is taken when it is
 * there, not followed: the programs of the Microsoft Store are aliases of the system (`WindowsApps\pwsh.exe`, a reparse point), which the JVM
 * does not follow and says are not there, though Windows runs them (measured). Elsewhere it is a regular file that can be executed.
 */
public fun findExecutable(name: String, searchPath: String, windows: Boolean = isWindows, pathExt: String? = System.getenv("PATHEXT")): Path? {
    val folders = searchPath.split(if (windows) ';' else ':').filter { it.isNotBlank() }
    val names = if (windows && '.' !in name) {
        (pathExt?.takeIf { it.isNotBlank() } ?: ".COM;.EXE;.BAT;.CMD").split(';').filter { it.isNotBlank() }.map { name + it.lowercase() }
    } else {
        listOf(name)
    }
    for (folder in folders) {
        for (candidate in names) {
            val file = try {
                Path.of(folder, candidate)
            } catch (_: InvalidPathException) {
                continue
            }
            val found = if (windows) Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(file)
            else Files.isRegularFile(file) && Files.isExecutable(file)
            if (found) return file
        }
    }
    return null
}
