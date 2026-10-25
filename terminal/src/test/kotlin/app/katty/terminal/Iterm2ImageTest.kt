package app.katty.terminal

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Iterm2ImageTest {
    private val geometry = CellGeometry(8, 16, 80, 24)

    // A picture of 4 by 2: red, green, blue, white on the first row and the same on the second, half transparent.
    private fun picture(type: Int = BufferedImage.TYPE_INT_ARGB): BufferedImage {
        val image = BufferedImage(4, 2, type)
        val colors = listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFFFF.toInt())
        for (x in 0 until 4) {
            image.setRGB(x, 0, colors[x])
            image.setRGB(x, 1, if (type == BufferedImage.TYPE_INT_ARGB) (colors[x] and 0x00FFFFFF) or (0x80 shl 24) else colors[x])
        }
        return image
    }

    private fun encoded(format: String, type: Int = BufferedImage.TYPE_INT_ARGB): String {
        val bytes = ByteArrayOutputStream().also { ImageIO.write(picture(type), format, it) }.toByteArray()
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun parse(arguments: String, file: String = encoded("png"), geometry: CellGeometry = this.geometry): Iterm2Picture? {
        val data = "\u001b]1337;File=$arguments:$file".toByteArray(Charsets.ISO_8859_1)
        return Iterm2Image.parse(data, 0, data.size, geometry)
    }

    // A PNG whose header says it is [width] by [height] pixels of gray: its data is one pixel's, which a decoder that trusts the header
    // never gets to.
    private fun pngThatClaims(width: Int, height: Int): ByteArray {
        val bytes = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_BYTE_GRAY), "png", it) }.toByteArray()
        // The signature (8 bytes), then the IHDR chunk: its length, its type, the width and the height, and its CRC after 13 bytes of data.
        java.nio.ByteBuffer.wrap(bytes).putInt(16, width).putInt(20, height)
        val crc = java.util.zip.CRC32().apply { update(bytes, 12, 17) }
        java.nio.ByteBuffer.wrap(bytes).putInt(29, crc.value.toInt())
        return bytes
    }

    // How many bytes the current thread allocated while [block] ran.
    private fun allocatedBy(block: () -> Unit): Long {
        val threads = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val before = threads.currentThreadAllocatedBytes
        block()
        return threads.currentThreadAllocatedBytes - before
    }

    private fun DecodedImage.pixel(x: Int, y: Int) = (0 until 4).map { rgba[(y * width + x) * 4 + it].toInt() and 0xFF }

    @Test
    fun readsAPngWithItsTransparency() {
        val picture = parse("inline=1")!!

        assertEquals(4, picture.image.width)
        assertEquals(2, picture.image.height)
        assertEquals(listOf(255, 0, 0, 255), picture.image.pixel(0, 0))
        assertEquals(listOf(0, 255, 0, 255), picture.image.pixel(1, 0))
        assertEquals(listOf(0, 0, 255, 255), picture.image.pixel(2, 0))
        assertEquals(listOf(255, 255, 255, 128), picture.image.pixel(3, 1), "Half transparent, not premultiplied")
        assertNull(picture.columns)
        assertNull(picture.rows)
        assertEquals(false, picture.keepCursor)
    }

    @Test
    fun readsTheFormatsThatTheJdkReads() {
        for (format in listOf("png", "gif", "bmp", "tiff")) {
            val type = if (format == "png" || format == "tiff") BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
            val file = try { encoded(format, type) } catch (e: Exception) { continue }
            val picture = assertNotNull(parse("inline=1", file), "A $format")
            assertEquals(4, picture.image.width, format)
        }
        val jpeg = parse("inline=1", encoded("jpeg", BufferedImage.TYPE_INT_RGB))
        assertEquals(4, jpeg!!.image.width, "A jpeg")
    }

    @Test
    fun theKeysAreNotCaseSensitiveAndTheFilesNameIsIgnored() {
        val picture = parse("Name=${Base64.getEncoder().encodeToString("a=b;c.png".toByteArray())};Size=123;INLINE=1;type=image/png")

        assertNotNull(picture)
    }

    @Test
    fun aFileThatIsNotInlineIsADownloadAndIsNotShown() {
        assertNull(parse("inline=0"))
        assertNull(parse("name=YQ=="))
    }

    @Test
    fun theSizeInCellsOfOneDimensionLeavesTheOtherToTheRatio() {
        val wide = parse("inline=1;width=10")!!
        assertEquals(10, wide.columns)
        assertNull(wide.rows)

        val high = parse("inline=1;height=3")!!
        assertNull(high.columns)
        assertEquals(3, high.rows)
    }

    @Test
    fun aSizeIsAtMostFourTimesTheTerminal() {
        assertEquals(4 * 80, parse("inline=1;width=1e10")!!.columns, "Not Int.MAX_VALUE")
        assertEquals(4 * 80, parse("inline=1;width=99999999999px")!!.columns)
        assertEquals(4 * 24, parse("inline=1;height=1000000%")!!.rows)
        assertEquals(60, parse("inline=1;height=60")!!.rows, "Taller than the screen is allowed: it scrolls")
    }

    @Test
    fun theSizeCanBeInPixelsOrInPercentOfTheTerminal() {
        assertEquals(5, parse("inline=1;width=33px")!!.columns, "33 pixels of 8 take 5 cells")
        assertEquals(1, parse("inline=1;height=5px")!!.rows)
        assertEquals(40, parse("inline=1;width=50%")!!.columns, "Half of 80 columns")
        assertEquals(12, parse("inline=1;height=50%")!!.rows, "Half of 24 rows")
    }

    @Test
    fun autoIsTheSizeOfTheImage() {
        val picture = parse("inline=1;width=auto;height=auto")!!

        assertNull(picture.columns)
        assertNull(picture.rows)
    }

    @Test
    fun anImageInABoxIsFittedByTheDimensionThatLimitsIt() {
        // The image is 4 by 2 pixels, so wide: in a box of 20 columns and 20 rows (160 by 320 pixels) the width is what limits it.
        val wide = parse("inline=1;width=20;height=20")!!
        assertEquals(20, wide.columns)
        assertNull(wide.rows)

        // In a box that is wide and short it is the height.
        val short = parse("inline=1;width=40;height=1")!!
        assertNull(short.columns)
        assertEquals(1, short.rows)
    }

    @Test
    fun withoutPreservingTheRatioTheImageIsStretchedToTheBox() {
        val picture = parse("inline=1;width=20;height=5;preserveAspectRatio=0")!!

        assertEquals(20, picture.columns)
        assertEquals(5, picture.rows)
    }

    @Test
    fun theCursorCanStayWhereItIs() {
        assertEquals(true, parse("inline=1;doNotMoveCursor=1")!!.keepCursor)
    }

    @Test
    fun somethingThatIsNotAnImageIsNothing() {
        assertNull(parse("inline=1", Base64.getEncoder().encodeToString("just text".toByteArray())))
        assertNull(parse("inline=1", "!!!not base64!!!"))
        assertNull(parse("inline=1", ""))
        val noColon = "\u001b]1337;File=inline=1".toByteArray()
        assertNull(Iterm2Image.parse(noColon, 0, noColon.size, geometry))
        val tooShort = "\u001b]1337".toByteArray()
        assertNull(Iterm2Image.parse(tooShort, 0, tooShort.size, geometry))
    }

    @Test
    fun aBase64WithLineBreaksIsRead() {
        val file = encoded("png").chunked(20).joinToString("\r\n")

        assertNotNull(parse("inline=1", file))
    }

    private fun chafaPath(): String? = listOf("/opt/homebrew/bin/chafa", "/usr/local/bin/chafa", "/usr/bin/chafa").firstOrNull { File(it).canExecute() }

    @Test
    fun readsWhatChafaSends() {
        val chafa = chafaPath() ?: return
        val png = File.createTempFile("katty-iterm", ".png").also { it.deleteOnExit() }
        val source = BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 40) for (x in 0 until 40) source.setRGB(x, y, if (x < 20) 0xFF0000 else 0x0000FF)
        ImageIO.write(source, "png", png)

        val process = ProcessBuilder(chafa, "-f", "iterm", "--probe", "off", "--size", "8x4", png.path).start()
        val output = process.inputStream.readBytes()
        process.waitFor()

        val start = (0 until output.size - 1).first { output[it] == 0x1B.toByte() && output[it + 1] == ']'.code.toByte() }
        val end = (start until output.size).first { output[it] == 0x07.toByte() }
        val picture = Iterm2Image.parse(output, start, end, geometry)
        assertNotNull(picture, "chafa's iTerm image: ${String(output, 0, minOf(output.size, 90), Charsets.ISO_8859_1)}")
        assertTrue(picture.image.width > 0 && picture.image.height > 0)
        assertEquals(8, picture.columns, "width=8, height=4, preserveAspectRatio=0: both")
        assertEquals(4, picture.rows)
        val left = picture.image.pixel(picture.image.width / 8, picture.image.height / 2)
        val right = picture.image.pixel(picture.image.width * 7 / 8, picture.image.height / 2)
        assertTrue(left[0] > 150 && left[2] < 100, "Red on the left: $left")
        assertTrue(right[2] > 150 && right[0] < 100, "Blue on the right: $right")
    }

    @Test
    fun anImageThatSaysItIsHugeIsRefusedBeforeItIsDecoded() {
        val file = Base64.getEncoder().encodeToString(pngThatClaims(20_000, 20_000))
        var picture: Iterm2Picture? = null

        val allocated = allocatedBy { picture = parse("inline=1", file) }

        assertNull(picture)
        assertTrue(allocated < 16L * 1024 * 1024, "$allocated bytes were allocated for an image that is refused")
    }
}
