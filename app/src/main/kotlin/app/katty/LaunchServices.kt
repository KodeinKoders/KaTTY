package app.katty

import app.katty.core.Ide
import app.katty.core.detectIdes
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
internal fun applicationInstalled(bundleId: String): Boolean {
    if (!isMac) return false
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
            if (name == MemorySegment.NULL) return@use false
            val array = urls.invoke(name, MemorySegment.NULL) as MemorySegment
            release.invoke(name)
            if (array == MemorySegment.NULL) return@use false
            try {
                val buffer = arena.allocate(4096)
                (0 until (count.invoke(array) as Long)).any { index ->
                    val url = valueAt.invoke(array, index) as MemorySegment
                    val ok = (path.invoke(url, 1.toByte(), buffer, 4096L) as Byte).toInt() != 0
                    ok && File(buffer.getString(0)).isDirectory
                }
            } finally {
                release.invoke(array)
            }
        }
    } catch (e: Throwable) {
        logger.debug("Could not ask LaunchServices for {}", bundleId, e)
        false
    }
}

/** The IDEs that are installed on this machine, with the bundle identifier that opens each. Asks LaunchServices: not for the UI thread. */
internal fun installedIdes(): Map<Ide, String> = detectIdes(::applicationInstalled)

/** Opens [directory] in the application [bundleId]: what `open -b` does. A failure is logged: there is nothing the user can do about it here. */
internal fun openInApplication(bundleId: String, directory: String) {
    try {
        ProcessBuilder("open", "-b", bundleId, directory).redirectErrorStream(true).start()
    } catch (e: Exception) {
        logger.warn("Could not open {} in {}", directory, bundleId, e)
    }
}
