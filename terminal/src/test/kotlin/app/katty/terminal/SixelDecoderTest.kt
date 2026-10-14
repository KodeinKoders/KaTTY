package app.katty.terminal

import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SixelDecoderTest {
    private val esc = "\u001b"

    // The string of a sixel image, which is what follows ESC P: the parameters, the q, and the data.
    private fun decode(data: String): DecodedImage? {
        val bytes = data.toByteArray(Charsets.ISO_8859_1)
        return SixelDecoder.decode(bytes, 0, bytes.size)
    }

    private fun DecodedImage.pixel(x: Int, y: Int): List<Int> =
        (0 until 4).map { rgba[(y * width + x) * 4 + it].toInt() and 0xFF }

    @Test
    fun oneBandOfOneColor() {
        val image = decode("0;1;0q#1;2;100;0;0#1~~~")!!

        assertEquals(3, image.width)
        assertEquals(6, image.height)
        for (y in 0 until 6) for (x in 0 until 3) assertEquals(listOf(255, 0, 0, 255), image.pixel(x, y), "($x, $y)")
    }

    @Test
    fun theBitsOfACharacterAreSixPixelsOfAColumnFromTheTop() {
        // '@' is 1: the top pixel, 'B' is 3: the two on top, 'w' is 0x38: the three at the bottom.
        val image = decode("0;1;0q#1;2;0;100;0#1@B w")!!

        assertEquals(listOf(0, 255, 0, 255), image.pixel(0, 0))
        assertEquals(0, image.pixel(0, 1)[3], "Only the top one")
        assertEquals(listOf(0, 255, 0, 255), image.pixel(1, 1))
        assertEquals(0, image.pixel(1, 2)[3])
    }

    @Test
    fun aRepeatDrawsManyColumns() {
        val image = decode("0;1;0q#1;2;0;0;100!10~")!!

        assertEquals(10, image.width)
        assertEquals(listOf(0, 0, 255, 255), image.pixel(9, 5))
    }

    @Test
    fun aNewBandStartsSixRowsDown() {
        val image = decode("0;1;0q#1;2;100;0;0~~-#2;2;0;100;0~~")!!

        assertEquals(12, image.height)
        assertEquals(listOf(255, 0, 0, 255), image.pixel(0, 5))
        assertEquals(listOf(0, 255, 0, 255), image.pixel(0, 6))
    }

    @Test
    fun aCarriageReturnDrawsAnotherColorOverTheSameBand() {
        val image = decode("0;1;0q#1;2;100;0;0~~\$#2;2;0;0;100@")!!

        assertEquals(listOf(0, 0, 255, 255), image.pixel(0, 0), "The blue is over the red")
        assertEquals(listOf(255, 0, 0, 255), image.pixel(0, 1))
        assertEquals(listOf(255, 0, 0, 255), image.pixel(1, 0), "Where the blue was not")
    }

    @Test
    fun aRegisterThatIsDefinedAgainHasTheNewColorFromThenOn() {
        val image = decode("0;1;0q#1;2;100;0;0~#1;2;0;100;0~")!!

        assertEquals(listOf(255, 0, 0, 255), image.pixel(0, 0))
        assertEquals(listOf(0, 255, 0, 255), image.pixel(1, 0))
    }

    @Test
    fun colorsCanBeGivenAsHueLightnessAndSaturationWithTheHueOfDec() {
        // DEC's hue starts at blue: 0 is blue, 120 is red, 240 is green.
        val image = decode("0;1;0q#1;1;0;50;100~#2;1;120;50;100~#3;1;240;50;100~#4;1;0;100;0~#5;1;0;0;0~")!!

        assertEquals(listOf(0, 0, 255, 255), image.pixel(0, 0), "Blue")
        assertEquals(listOf(255, 0, 0, 255), image.pixel(1, 0), "Red")
        assertEquals(listOf(0, 255, 0, 255), image.pixel(2, 0), "Green")
        assertEquals(listOf(255, 255, 255, 255), image.pixel(3, 0), "White")
        assertEquals(listOf(0, 0, 0, 255), image.pixel(4, 0), "Black")
    }

    @Test
    fun theFirstRegistersAreTheColorsOfTheVt340() {
        val image = decode("0;1;0q#1~#2~#15~")!!

        assertEquals(listOf(51, 51, 204, 255), image.pixel(0, 0), "20, 20, 80 percent")
        assertEquals(listOf(204, 33, 33, 255), image.pixel(1, 0), "80, 13, 13 percent")
        assertEquals(listOf(204, 204, 204, 255), image.pixel(2, 0))
    }

    @Test
    fun theRasterAttributesGiveTheSizeOfThePicture() {
        val image = decode("0;1;0q\"1;1;8;12#1;2;100;0;0~")!!

        assertEquals(8, image.width)
        assertEquals(12, image.height)
        assertEquals(listOf(255, 0, 0, 255), image.pixel(0, 0))
        assertEquals(0, image.pixel(7, 11)[3], "What is not drawn is transparent")
    }

    @Test
    fun whatIsDrawnPastTheRasterAttributesIsKept() {
        val image = decode("0;1;0q\"1;1;2;6#1;2;100;0;0!5~")!!

        assertEquals(5, image.width)
    }

    @Test
    fun whatIsNotDrawnIsTransparent() {
        val image = decode("0;1;0q#1;2;100;100;100?~")!!

        assertEquals(0, image.pixel(0, 0)[3])
        assertEquals(255, image.pixel(1, 0)[3])
    }

    @Test
    fun lineBreaksAndStrayBytesAreSkipped() {
        val image = decode("0;1;0q\r\n#1;2;100;0;0\n~\r\n~ ")!!

        assertEquals(2, image.width)
    }

    @Test
    fun somethingThatIsNotAPictureIsNothing() {
        assertNull(decode(""))
        assertNull(decode("0;1;0q"), "No data")
        assertNull(decode("0;1;0"), "No q")
        assertNull(decode("abc q#1~"), "Parameters that are not")
        assertNull(decode("q????"), "Nothing is drawn: there are no pixels")
    }

    @Test
    fun aPictureThatIsTooBigIsRefused() {
        assertNull(decode("0;1;0q\"1;1;20000;20000#1~"))
        // A raster attribute that is only big is not a reason: the pixels are what count.
        assertNotNull(decode("0;1;0q\"1;1;100;100#1~"))
    }

    @Test
    fun aTruncatedStreamIsWhatWasReceived() {
        val image = decode("0;1;0q#1;2;100;0;0!4~-!4")!!

        assertEquals(4, image.width)
        assertEquals(6, image.height, "The band that has nothing is not a row of the picture")
    }

    @Test
    fun theRegistersOfAHugeNumberAreHandled() {
        val image = decode("0;1;0q#99999;2;100;0;0~#1~")!!

        assertEquals(2, image.width)
    }

    private fun chafaPath(): String? = listOf("/opt/homebrew/bin/chafa", "/usr/local/bin/chafa", "/usr/bin/chafa").firstOrNull { File(it).canExecute() }

    @Test
    fun readsWhatChafaSends() {
        val chafa = chafaPath() ?: return
        val png = File.createTempFile("katty-sixel", ".png").also { it.deleteOnExit() }
        val source = BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 40) for (x in 0 until 40) source.setRGB(x, y, if (x < 20) 0xFF0000 else 0x0000FF)
        ImageIO.write(source, "png", png)

        val process = ProcessBuilder(chafa, "-f", "sixel", "--probe", "off", "--size", "8x4", png.path).redirectErrorStream(false).start()
        val output = process.inputStream.readBytes()
        process.waitFor()

        // What follows the ESC P, up to the ESC \ that ends it.
        val start = (1 until output.size).first { output[it] == 'P'.code.toByte() && output[it - 1] == 0x1B.toByte() } + 1
        val end = (start until output.size - 1).first { output[it] == 0x1B.toByte() && output[it + 1] == '\\'.code.toByte() }
        val real = SixelDecoder.decode(output, start, end)
        assertNotNull(real, "chafa's Sixel: ${String(output, 0, minOf(output.size, 80), Charsets.ISO_8859_1)}")
        assertTrue(real.width > 0 && real.height > 0)
        val left = real.pixel(real.width / 8, real.height / 2)
        val right = real.pixel(real.width * 7 / 8, real.height / 2)
        assertTrue(left[0] > 150 && left[2] < 100, "Red on the left: $left")
        assertTrue(right[2] > 150 && right[0] < 100, "Blue on the right: $right")
        Files.deleteIfExists(png.toPath())
    }
}
