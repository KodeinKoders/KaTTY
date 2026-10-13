package app.katty.ghostty

import org.slf4j.LoggerFactory
import java.nio.file.Files

private val logger = LoggerFactory.getLogger(NativeLibrary::class.java)

/**
 * Loads the libghostty-vt dylib bundled in the jar, once.
 *
 * The jextract-generated bindings look their functions up with `SymbolLookup.loaderLookup()`, which
 * only sees libraries loaded with `System.load`. Every class that calls into the bindings must
 * therefore call [ensureLoaded] first.
 */
internal object NativeLibrary {
    private const val LIBRARY_NAME = "libghostty-vt"

    init {
        load()
    }

    fun ensureLoaded() {
        // Referencing the object runs its initializer, which is all there is to do.
    }

    private fun load() {
        val (os, extension) = when {
            "mac" in System.getProperty("os.name").lowercase() -> "macos" to "dylib"
            else -> throw unsupported()
        }
        val arch = when (System.getProperty("os.arch").lowercase()) {
            "aarch64", "arm64" -> "aarch64"
            "x86_64", "amd64" -> "x86_64"
            else -> throw unsupported()
        }
        val resource = "/app/katty/ghostty/native/$os-$arch/$LIBRARY_NAME.$extension"
        val stream = NativeLibrary::class.java.getResourceAsStream(resource) ?: throw unsupported()

        // The dylib has to be a real file to be loaded, so it is extracted next to a temporary directory
        // that is removed on exit.
        val directory = Files.createTempDirectory("katty-ghostty")
        val file = directory.resolve("$LIBRARY_NAME.$extension")
        stream.use { Files.copy(it, file) }
        file.toFile().deleteOnExit()
        directory.toFile().deleteOnExit()

        System.load(file.toAbsolutePath().toString())
        logger.debug("Loaded libghostty-vt for {}-{} from {}", os, arch, file)
    }

    private fun unsupported() = UnsupportedOperationException(
        "libghostty-vt is not available for ${System.getProperty("os.name")} " +
            "on ${System.getProperty("os.arch")}",
    )
}
