package app.katty.ghostty

import app.katty.ghostty.ffm.GhosttySysDecodePngFn
import app.katty.ghostty.ffm.GhosttySysImage
import app.katty.ghostty.ffm.GhosttyVt
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import javax.imageio.ImageIO

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

    // The file that the library was loaded from.
    private lateinit var libraryFile: java.nio.file.Path

    /**
     * What identifies the library that is loaded: a hash of the file. Nothing in the library does, since it records neither its commit nor a
     * version that changes, and the snapshots that it makes are only good for the same one.
     */
    val fingerprint: String by lazy {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        Files.newInputStream(libraryFile).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
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

        libraryFile = file
        System.load(file.toAbsolutePath().toString())
        logger.debug("Loaded libghostty-vt for {}-{} from {}", os, arch, file)

        installPngDecoder()
    }

    // The terminal needs a decoder to accept PNG images, and the setting is the library's, not a terminal's: it is done
    // once, and the stub stays for as long as the process does.
    private fun installPngDecoder() {
        val stub = GhosttySysDecodePngFn.allocate(
            GhosttySysDecodePngFn.Function { _, allocator, data, length, out ->
                // An exception must never cross into native code: it would take the JVM down.
                try {
                    decodePng(allocator, data, length, out)
                } catch (e: Throwable) {
                    logger.warn("Could not decode a PNG image", e)
                    false
                }
            },
            Arena.global(),
        )
        checkResult(GhosttyVt.ghostty_sys_set(GhosttyVt.GHOSTTY_SYS_OPT_DECODE_PNG(), stub), "ghostty_sys_set(DECODE_PNG)")
    }

    /**
     * Decodes a PNG to straight RGBA, into a buffer that the library owns once this returns `true`.
     *
     * It goes through an ARGB image of the JDK, which turns palettes, gray, 16 bits and the other variants into the one
     * layout that the library wants.
     */
    private fun decodePng(allocator: MemorySegment, data: MemorySegment, length: Long, out: MemorySegment): Boolean {
        val png = data.reinterpret(length).toArray(ValueLayout.JAVA_BYTE)
        val image = ImageIO.read(ByteArrayInputStream(png)) ?: return false
        val width = image.width
        val height = image.height
        val size = width.toLong() * height * 4
        if (size <= 0 || size > Int.MAX_VALUE) return false

        val argb = if (image.type == BufferedImage.TYPE_INT_ARGB) {
            image
        } else {
            BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { copy ->
                val graphics = copy.createGraphics()
                try {
                    graphics.drawImage(image, 0, 0, null)
                } finally {
                    graphics.dispose()
                }
            }
        }
        val pixels = argb.getRGB(0, 0, width, height, null, 0, width)

        // The allocator limits what an image may take: a null is a refusal.
        val buffer = GhosttyVt.ghostty_alloc(allocator, size)
        if (buffer == MemorySegment.NULL) return false
        val rgba = buffer.reinterpret(size)
        for (i in pixels.indices) {
            val p = pixels[i]
            rgba.set(ValueLayout.JAVA_BYTE, i * 4L, (p shr 16).toByte())
            rgba.set(ValueLayout.JAVA_BYTE, i * 4L + 1, (p shr 8).toByte())
            rgba.set(ValueLayout.JAVA_BYTE, i * 4L + 2, p.toByte())
            rgba.set(ValueLayout.JAVA_BYTE, i * 4L + 3, (p ushr 24).toByte())
        }
        // The struct comes as a bare address: it has to be given its size to be written to.
        val result = out.reinterpret(GhosttySysImage.layout().byteSize())
        GhosttySysImage.width(result, width)
        GhosttySysImage.height(result, height)
        GhosttySysImage.data(result, buffer)
        GhosttySysImage.data_len(result, size)
        return true
    }

    private fun unsupported() = UnsupportedOperationException(
        "libghostty-vt is not available for ${System.getProperty("os.name")} " +
            "on ${System.getProperty("os.arch")}",
    )
}
