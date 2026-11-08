package app.katty

import app.katty.core.Ide
import app.katty.core.detectIdes
import app.katty.core.openFileCommand
import app.katty.terminal.TerminalFilePosition
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout

private val logger = LoggerFactory.getLogger("app.katty.LaunchServices")

private const val UTF8 = 0x08000100

private val isMac = System.getProperty("os.name").startsWith("Mac")

/**
 * Whether an application of macOS with the bundle identifier [bundleId] is installed, wherever it is: LaunchServices knows, which `/Applications`
 * would not say for the ones that JetBrains Toolbox puts in `~/Applications`. LaunchServices can remember an application that was deleted, so
 * the folder it names has to be there. Any failure says it is not installed, and nothing is installed off macOS.
 */
internal fun applicationInstalled(bundleId: String): Boolean = applicationPath(bundleId) != null

/** Where the application [bundleId] is installed (its bundle, `/Applications/Xcode.app`), `null` when it is not: see [applicationInstalled]. */
internal fun applicationPath(bundleId: String): String? {
    if (!isMac) return null
    return try {
        Arena.ofConfined().use { arena ->
            val linker = Linker.nativeLinker()
            val coreFoundation = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", arena)
            val coreServices = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreServices.framework/CoreServices", arena)
            fun handle(lookup: SymbolLookup, name: String, descriptor: FunctionDescriptor) =
                linker.downcallHandle(lookup.find(name).orElseThrow { IllegalStateException("$name is not found") }, descriptor)

            val createString = handle(coreFoundation, "CFStringCreateWithCString", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT))
            val release = handle(coreFoundation, "CFRelease", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
            val count = handle(coreFoundation, "CFArrayGetCount", FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS))
            val valueAt = handle(coreFoundation, "CFArrayGetValueAtIndex", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG))
            val path = handle(coreFoundation, "CFURLGetFileSystemRepresentation", FunctionDescriptor.of(ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG))
            val urls = handle(coreServices, "LSCopyApplicationURLsForBundleIdentifier", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS))

            val name = createString.invoke(MemorySegment.NULL, arena.allocateFrom(bundleId), UTF8) as MemorySegment
            if (name == MemorySegment.NULL) return@use null
            val array = urls.invoke(name, MemorySegment.NULL) as MemorySegment
            release.invoke(name)
            if (array == MemorySegment.NULL) return@use null
            try {
                val buffer = arena.allocate(4096)
                (0 until (count.invoke(array) as Long)).firstNotNullOfOrNull { index ->
                    val url = valueAt.invoke(array, index) as MemorySegment
                    val ok = (path.invoke(url, 1.toByte(), buffer, 4096L) as Byte).toInt() != 0
                    buffer.getString(0).takeIf { ok && File(it).isDirectory }
                }
            } finally {
                release.invoke(array)
            }
        }
    } catch (e: Throwable) {
        logger.debug("Could not ask LaunchServices for {}", bundleId, e)
        null
    }
}

/** The IDEs that are installed on this machine, with the bundle identifier that opens each. Asks LaunchServices: not for the UI thread. */
internal fun installedIdes(): Map<Ide, String> = detectIdes(::applicationInstalled)

/**
 * Opens [file] at its line in [ide], installed as [bundleId], with the command that [openFileCommand] makes for it: the tool of its bundle, or
 * `open`. It asks LaunchServices where the bundle is: not for the UI thread. A failure is logged, as for [openInApplication].
 */
internal fun openFileInApplication(ide: Ide, bundleId: String, file: TerminalFilePosition) {
    val command = openFileCommand(ide, bundleId, applicationPath(bundleId), file.path, file.line, file.column, file.directory)
    try {
        // Nobody reads what the tool says: it goes nowhere, so that a tool that writes much does not wait for a reader.
        ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    } catch (e: Exception) {
        logger.warn("Could not open {} in {}", file.path, bundleId, e)
    }
}

/** Opens [directory] in the application [bundleId]: what `open -b` does. A failure is logged: there is nothing the user can do about it here. */
internal fun openInApplication(bundleId: String, directory: String) {
    try {
        ProcessBuilder("open", "-b", bundleId, directory).redirectErrorStream(true).start()
    } catch (e: Exception) {
        logger.warn("Could not open {} in {}", directory, bundleId, e)
    }
}
