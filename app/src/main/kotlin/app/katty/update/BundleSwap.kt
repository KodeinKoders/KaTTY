package app.katty.update

import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private val logger = LoggerFactory.getLogger("app.katty.update.BundleSwap")

// renamex_np's flag that exchanges the two names (sys/stdio.h).
private const val RENAME_SWAP = 0x2
private const val ENOTSUP = 45

private const val LSREGISTER = "/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister"

/**
 * Puts [staged] in the place of [installed], and [installed] in the place of [staged], so that the application is never missing from its folder:
 * `renamex_np` with `RENAME_SWAP` exchanges the two names at once (APFS and HFS+). A volume that cannot ([ENOTSUP]) gets two renames, and the old
 * application is put back when the second fails. The process that runs from [installed] goes on: its files are open, a rename changes no inode,
 * and it does not touch the pages that the signature covers.
 *
 * Throws when nothing was changed.
 */
internal fun swapBundles(staged: Path, installed: Path, exchange: (Path, Path) -> Int = ::renameSwap) {
    val errno = exchange(staged, installed)
    if (errno == 0) return
    if (errno != ENOTSUP) throw IOException("the new version could not take the place of the application (errno $errno)")
    logger.info("The volume of {} cannot swap two names: it is done with two renames", installed)
    val aside = installed.resolveSibling(".${installed.fileName}.old")
    Files.move(installed, aside, StandardCopyOption.ATOMIC_MOVE)
    try {
        Files.move(staged, installed, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: Exception) {
        Files.move(aside, installed, StandardCopyOption.ATOMIC_MOVE)
        throw e
    }
    Files.move(aside, staged, StandardCopyOption.ATOMIC_MOVE)
}

/** `renamex_np(from, to, RENAME_SWAP)` through FFM: 0, or the `errno` of its failure. */
private fun renameSwap(from: Path, to: Path): Int = Arena.ofConfined().use { arena ->
    val linker = Linker.nativeLinker()
    val errnoLayout = Linker.Option.captureStateLayout()
    val renamex = linker.downcallHandle(
        linker.defaultLookup().find("renamex_np").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
        Linker.Option.captureCallState("errno"),
    )
    val state = arena.allocate(errnoLayout)
    val result = renamex.invoke(state, arena.allocateFrom(from.toString()), arena.allocateFrom(to.toString()), RENAME_SWAP) as Int
    if (result == 0) 0 else errnoLayout.varHandle(java.lang.foreign.MemoryLayout.PathElement.groupElement("errno")).get(state, 0L) as Int
}

/**
 * Starts what finishes an update once the application has exited, in a process of its own that outlives it (`trap '' HUP`): it waits for [pid]
 * (60 s at most), deletes [old] (the previous version, where the new one waited), tells Launch Services of [app], and opens it again when
 * [relaunch]. What it prints goes to [log]. A launch that finds [old] still there deletes it (see [Updater]).
 */
internal fun startFinisher(
    pid: Long,
    old: Path,
    app: Path,
    relaunch: Boolean,
    log: Path,
    open: List<String> = listOf("/usr/bin/open"),
    register: String = LSREGISTER,
): Process {
    Files.createDirectories(log.parent)
    val script = """
        trap '' HUP
        i=0
        while kill -0 "${'$'}1" 2>/dev/null && [ ${'$'}i -lt 300 ]; do sleep 0.2; i=${'$'}((i + 1)); done
        echo "KaTTY ${'$'}1 has exited: deleting ${'$'}2"
        rm -rf "${'$'}2"
        "${'$'}5" -f "${'$'}3" || true
        if [ "${'$'}4" = true ]; then shift 5; echo "Opening ${'$'}0"; "${'$'}@"; fi
    """.trimIndent()
    // $0 of the script is the application, and the command that opens it is the arguments after the first five.
    val arguments = listOf(pid.toString(), old.toString(), app.toString(), relaunch.toString(), register)
    return ProcessBuilder(listOf("/bin/sh", "-c", script, app.toString()) + arguments + open + app.toString())
        .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.to(log.toFile()))
        .start()
}
