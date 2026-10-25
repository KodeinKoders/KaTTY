package app.katty.ghostty

import app.katty.ghostty.ffm.GhosttySysDecodePngFn
import app.katty.ghostty.ffm.GhosttySysImage
import app.katty.ghostty.ffm.GhosttyVt
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

private val logger = LoggerFactory.getLogger(NativeLibrary::class.java)

// The folders that the library is extracted to, and the ones that earlier versions made with `createTempDirectory`, a number after the prefix.
private const val EXTRACTION_PREFIX = "katty-ghostty"
private val LEFTOVER_EXTRACTION = Regex("$EXTRACTION_PREFIX[0-9]+")

/** The SHA-256 of [bytes], in hexadecimal. */
internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * The file [fileName] in `katty-ghostty-<hash>` of [parent], with [bytes] in it, whose SHA-256 is [hash]: the one that is there when it holds them,
 * else written again. A new copy is written to a file of its own and moved in place, so that two processes that start together never load half
 * of one.
 */
internal fun extractLibrary(bytes: ByteArray, hash: String, parent: Path, fileName: String): Path {
    val directory = Files.createDirectories(parent.resolve("$EXTRACTION_PREFIX-${hash.take(16)}"))
    val file = directory.resolve(fileName)
    val current = try {
        Files.isRegularFile(file) && Files.size(file) == bytes.size.toLong() && sha256(Files.readAllBytes(file)) == hash
    } catch (e: IOException) {
        false
    }
    if (current) return file
    val written = Files.createTempFile(directory, ".$fileName", ".tmp")
    try {
        Files.write(written, bytes)
        Files.move(written, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(written)
    }
    return file
}

/**
 * Removes what earlier versions left in [parent]: a folder for each run, which was never deleted. A library that a process still runs from can
 * be removed on macOS. What cannot be removed is left.
 */
internal fun removeLeftoverExtractions(parent: Path) {
    try {
        Files.newDirectoryStream(parent) { LEFTOVER_EXTRACTION.matches(it.fileName.toString()) && Files.isDirectory(it) }.use { folders ->
            for (folder in folders) {
                try {
                    Files.newDirectoryStream(folder).use { files -> files.forEach(Files::deleteIfExists) }
                    Files.deleteIfExists(folder)
                } catch (e: IOException) {
                    logger.debug("Could not remove {}", folder, e)
                }
            }
        }
    } catch (e: IOException) {
        logger.debug("Could not look for what earlier versions left in {}", parent, e)
    }
}

/**
 * Loads the libghostty-vt dylib bundled in the jar, once.
 *
 * The jextract-generated bindings look their functions up with `SymbolLookup.loaderLookup()`, which
 * only sees libraries loaded with `System.load`. Every class that calls into the bindings must
 * therefore call [ensureLoaded] first.
 */
internal object NativeLibrary {
    private const val LIBRARY_NAME = "libghostty-vt"

    // The most pixels that a PNG of the Kitty protocol may have, as for the images that KaTTY decodes itself (Sixel, iTerm2): 4096 × 4096,
    // 64 MB once decoded. A program that shows a bigger picture scales it to the window first (`kitten icat` does).
    private const val MAX_PNG_PIXELS = 16L * 1024 * 1024

    init {
        load()
    }

    /**
     * What identifies the library that is loaded: a SHA-256 of it. Nothing in the library does, since it records neither its commit nor a
     * version that changes, and the snapshots that it makes are only good for the same one.
     */
    lateinit var fingerprint: String
        private set

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
        val bytes = NativeLibrary::class.java.getResourceAsStream(resource)?.use { it.readBytes() } ?: throw unsupported()
        fingerprint = sha256(bytes)

        // The dylib has to be a real file to be loaded: it is extracted once, to a folder of the temporary directory named by what it is, which
        // every run (and every test) of the same library uses again. macOS cleans its temporary directory of what is not used for days: it is
        // then extracted again.
        val temporary = Path.of(System.getProperty("java.io.tmpdir"))
        val file = extractLibrary(bytes, fingerprint, temporary, "$LIBRARY_NAME.$extension")
        removeLeftoverExtractions(temporary)

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
     * The size is read from the header first, and the buffer is asked of the library's allocator (which limits what an image may take)
     * before anything is decoded: a PNG of a few hundred bytes can say that it is 20000 × 20000, and `ImageIO.read` makes the image of that
     * size before it reads a pixel. The JDK's `getRGB` turns palettes, gray, 16 bits and the other variants into the one layout that the
     * library wants, a row at a time.
     */
    private fun decodePng(allocator: MemorySegment, data: MemorySegment, length: Long, out: MemorySegment): Boolean {
        val png = data.reinterpret(length).toArray(ValueLayout.JAVA_BYTE)
        // In memory: ImageIO's default cache would copy every image to a temporary file.
        MemoryCacheImageInputStream(ByteArrayInputStream(png)).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return false
            try {
                reader.setInput(input, true, true)
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PNG_PIXELS) return false
                val size = width.toLong() * height * 4

                // A null is a refusal of the allocator.
                val buffer = GhosttyVt.ghostty_alloc(allocator, size)
                if (buffer == MemorySegment.NULL) return false
                var filled = false
                try {
                    val image = reader.read(0)
                    val rgba = buffer.reinterpret(size)
                    val argb = IntArray(width)
                    val row = ByteArray(width * 4)
                    for (y in 0 until height) {
                        image.getRGB(0, y, width, 1, argb, 0, width)
                        for (x in 0 until width) {
                            val p = argb[x]
                            row[x * 4] = (p shr 16).toByte()
                            row[x * 4 + 1] = (p shr 8).toByte()
                            row[x * 4 + 2] = p.toByte()
                            row[x * 4 + 3] = (p ushr 24).toByte()
                        }
                        MemorySegment.copy(row, 0, rgba, ValueLayout.JAVA_BYTE, y.toLong() * width * 4, row.size)
                    }
                    // The struct comes as a bare address: it has to be given its size to be written to.
                    val result = out.reinterpret(GhosttySysImage.layout().byteSize())
                    GhosttySysImage.width(result, width)
                    GhosttySysImage.height(result, height)
                    GhosttySysImage.data(result, buffer)
                    GhosttySysImage.data_len(result, size)
                    filled = true
                    return true
                } finally {
                    // On a failure the callback frees what it allocated: the library does not read the result.
                    if (!filled) GhosttyVt.ghostty_free(allocator, buffer, size)
                }
            } finally {
                reader.dispose()
            }
        }
    }

    private fun unsupported() = UnsupportedOperationException(
        "libghostty-vt is not available for ${System.getProperty("os.name")} " +
            "on ${System.getProperty("os.arch")}",
    )
}
