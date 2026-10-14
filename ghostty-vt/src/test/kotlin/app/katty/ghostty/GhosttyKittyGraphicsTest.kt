package app.katty.ghostty

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GhosttyKittyGraphicsTest {
    private val terminal = GhosttyTerminal(cols = 20, rows = 10, imageStorageLimitBytes = 10_000_000)
    private val state = GhosttyRenderState()

    init {
        // Cells of 10 by 20 pixels: the geometry of a placement depends on it.
        terminal.resize(20, 10, 10, 20)
    }

    @AfterTest
    fun close() {
        state.close()
        terminal.close()
    }

    // ESC _ G control ; payload ESC \ , as a program writes it.
    private fun kitty(control: String, payload: ByteArray = ByteArray(0)): String =
        "\u001b_G$control;${Base64.getEncoder().encodeToString(payload)}\u001b\\"

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    // A 2 by 2 image: red, green, blue, and white that is half transparent.
    private val rgba = bytes(255, 0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 255, 255, 255, 255, 128)

    private fun placements() = terminal.kittyPlacements()

    @Test
    fun aRawRgbaImageIsStoredAndPlacedAtTheCursor() {
        terminal.vtWrite("ab" + kitty("a=T,f=32,s=2,v=2,i=1", rgba))

        val placement = placements().single()

        assertEquals(1, placement.imageId)
        assertEquals(2, placement.viewportCol, "Where the cursor was")
        assertEquals(0, placement.viewportRow)
        assertTrue(placement.visible)
        assertFalse(placement.isVirtual)
        // 2 pixels by 2: one cell of 10 by 20.
        assertEquals(2, placement.pixelWidth)
        assertEquals(2, placement.pixelHeight)
        assertEquals(1, placement.gridCols)
        assertEquals(1, placement.gridRows)
        assertContentEquals(rgba, terminal.kittyImage(1)!!.toRgba())
        assertEquals(2, terminal.kittyImage(1)!!.width)
    }

    @Test
    fun theSizeInCellsIsWhatTheProgramAskedFor() {
        terminal.vtWrite(kitty("a=T,f=32,s=2,v=2,i=1,c=5,r=3", rgba))

        val placement = placements().single()

        assertEquals(5, placement.gridCols)
        assertEquals(3, placement.gridRows)
        // And the pixels that it is shown at: 5 cells of 10 and 3 of 20.
        assertEquals(50, placement.pixelWidth)
        assertEquals(60, placement.pixelHeight)
    }

    @Test
    fun anRgbImageIsOpaque() {
        val rgb = bytes(255, 0, 0, 0, 255, 0, 0, 0, 255, 10, 20, 30)
        terminal.vtWrite(kitty("a=T,f=24,s=2,v=2,i=1", rgb))

        val image = terminal.kittyImage(1)!!

        assertEquals(GhosttyImageFormat.RGB, image.format)
        assertContentEquals(bytes(255, 0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 255, 10, 20, 30, 255), image.toRgba())
    }

    @Test
    fun aGrayImageIsGrayAndOpaque() {
        // The format of gray images is 8 bits a pixel, which the protocol only has in the form of its own files.
        val image = GhosttyImage(1, 1, 2, 1, GhosttyImageFormat.GRAY, bytes(10, 200))

        assertContentEquals(bytes(10, 10, 10, 255, 200, 200, 200, 255), image.toRgba())
    }

    @Test
    fun aGrayImageWithAlphaKeepsItsAlpha() {
        val image = GhosttyImage(1, 1, 2, 1, GhosttyImageFormat.GRAY_ALPHA, bytes(10, 20, 200, 30))

        assertContentEquals(bytes(10, 10, 10, 20, 200, 200, 200, 30), image.toRgba())
    }

    private fun png(width: Int, height: Int, type: Int, paint: (BufferedImage) -> Unit): ByteArray {
        val image = BufferedImage(width, height, type)
        paint(image)
        val out = ByteArrayOutputStream()
        check(ImageIO.write(image, "png", out))
        return out.toByteArray()
    }

    @Test
    fun aPngWithAlphaIsDecoded() {
        val data = png(2, 1, BufferedImage.TYPE_INT_ARGB) {
            it.setRGB(0, 0, 0xFFFF0000.toInt())
            it.setRGB(1, 0, 0x8000FF00.toInt())
        }
        terminal.vtWrite(kitty("a=T,f=100,i=1", data))

        val image = terminal.kittyImage(1)!!

        assertEquals(2, image.width)
        assertEquals(1, image.height)
        assertContentEquals(bytes(255, 0, 0, 255, 0, 255, 0, 128), image.toRgba())
    }

    @Test
    fun aPngWithAPaletteOrGrayIsDecoded() {
        val indexed = png(2, 1, BufferedImage.TYPE_BYTE_INDEXED) {
            it.setRGB(0, 0, 0xFF0000FF.toInt())
            it.setRGB(1, 0, 0xFFFFFF00.toInt())
        }
        terminal.vtWrite(kitty("a=T,f=100,i=1", indexed))
        val colors = terminal.kittyImage(1)!!.toRgba()
        assertEquals(255, colors[3].toInt() and 0xFF, "Opaque")
        // Blue, then yellow: whichever palette it chose, the colors are close.
        assertTrue((colors[2].toInt() and 0xFF) > 200 && (colors[0].toInt() and 0xFF) < 60, "The first is blue")
        assertTrue((colors[4].toInt() and 0xFF) > 200 && (colors[6].toInt() and 0xFF) < 60, "The second is yellow")

        val gray = png(2, 1, BufferedImage.TYPE_BYTE_GRAY) {
            it.raster.setSample(0, 0, 0, 0)
            it.raster.setSample(1, 0, 0, 255)
        }
        terminal.vtWrite(kitty("a=T,f=100,i=2", gray))
        val grays = terminal.kittyImage(2)!!.toRgba()
        assertEquals(0, grays[0].toInt() and 0xFF)
        assertEquals(255, grays[4].toInt() and 0xFF)
        assertEquals(255, grays[7].toInt() and 0xFF)
    }

    @Test
    fun aPngThatCannotBeDecodedIsRefused() {
        terminal.vtWrite(kitty("a=T,f=100,i=1", bytes(1, 2, 3, 4, 5)))

        assertNull(terminal.kittyImage(1))
        assertEquals(emptyList(), placements())
    }

    @Test
    fun anImageSentInChunksIsOneImage() {
        val base64 = Base64.getEncoder().encodeToString(rgba)
        val first = base64.take(8)
        val rest = base64.drop(8)

        terminal.vtWrite("\u001b_Ga=T,f=32,s=2,v=2,i=1,m=1;$first\u001b\\")
        assertNull(terminal.kittyImage(1), "Not all of it has arrived")
        terminal.vtWrite("\u001b_Gm=0;$rest\u001b\\")

        assertContentEquals(rgba, terminal.kittyImage(1)!!.toRgba())
        assertEquals(1, placements().size)
    }

    @Test
    fun aCompressedImageIsInflated() {
        val deflater = Deflater()
        deflater.setInput(rgba)
        deflater.finish()
        val buffer = ByteArray(200)
        val compressed = buffer.copyOf(deflater.deflate(buffer))

        terminal.vtWrite(kitty("a=T,f=32,s=2,v=2,i=1,o=z", compressed))

        assertContentEquals(rgba, terminal.kittyImage(1)!!.toRgba())
    }

    @Test
    fun theGenerationChangesWithTheStorageAndWithTheImage() {
        assertEquals(0, terminal.kittyGeneration, "Never used")

        terminal.vtWrite(kitty("a=T,f=32,s=2,v=2,i=1", rgba))
        val first = terminal.kittyGeneration
        val imageGeneration = placements().single().imageGeneration
        assertNotEquals(0, first)

        // The same id again, with other pixels, of the same size.
        terminal.vtWrite(kitty("a=T,f=32,s=2,v=2,i=1", rgba.reversedArray()))

        assertNotEquals(first, terminal.kittyGeneration)
        assertNotEquals(imageGeneration, placements().last().imageGeneration)
        assertContentEquals(rgba.reversedArray(), terminal.kittyImage(1)!!.toRgba())
    }

    @Test
    fun anImageScrollsWithTheText() {
        terminal.vtWrite(kitty("a=T,f=32,s=2,v=2,i=1", rgba))
        assertEquals(0, placements().single().viewportRow)

        terminal.vtWrite("\r\n\r\n\r\n")
        assertEquals(0, placements().single().viewportRow, "The screen did not scroll yet")
        for (i in 1..12) terminal.vtWrite("line $i\r\n")

        val placement = placements().single()
        assertTrue(placement.viewportRow < 0, "It is above the viewport: ${placement.viewportRow}")
        assertFalse(placement.visible)
    }

    @Test
    fun aPartlyScrolledPlacementIsVisibleAtANegativeRow() {
        terminal.vtWrite(kitty("a=T,f=32,s=2,v=2,i=1,c=2,r=4", rgba))
        // Four rows tall, and the cursor is on its last: 9 lines scroll the screen by 3, which leaves one row of it.
        terminal.vtWrite("\r\n".repeat(9))

        val placement = placements().single()

        assertEquals(true, placement.visible, "One of its rows is still in the viewport: $placement")
        assertTrue(placement.viewportRow < 0)
    }

    @Test
    fun deletingRemovesThePlacementAndTheImageWhenAskedTo() {
        terminal.vtWrite(kitty("a=T,f=32,s=2,v=2,i=1", rgba))

        terminal.vtWrite("\u001b_Ga=d,d=a\u001b\\")
        assertEquals(emptyList(), placements(), "Deleted visible placements")
        assertNotNull(terminal.kittyImage(1), "The image is kept: a lowercase d keeps the data")

        terminal.vtWrite("\u001b_Ga=d,d=I,i=1\u001b\\")
        assertNull(terminal.kittyImage(1), "An uppercase one frees it")
    }

    @Test
    fun theAlternateScreenHasItsOwnImages() {
        terminal.vtWrite(kitty("a=T,f=32,s=2,v=2,i=1", rgba))

        terminal.vtWrite("\u001b[?1049h")
        assertEquals(emptyList(), placements())
        terminal.vtWrite(kitty("a=T,f=32,s=2,v=2,i=2", rgba))
        assertEquals(listOf(2), placements().map { it.imageId })

        terminal.vtWrite("\u001b[?1049l")
        assertEquals(listOf(1), placements().map { it.imageId })
    }

    @Test
    fun anImageThatDoesNotFitTheLimitIsRefused() {
        GhosttyTerminal(cols = 20, rows = 10, imageStorageLimitBytes = 8).use { small ->
            small.vtWrite(kitty("a=T,f=32,s=2,v=2,i=1", rgba))

            assertEquals(emptyList(), small.kittyPlacements())
            assertNull(small.kittyImage(1))
        }
    }

    @Test
    fun aLimitOfZeroTurnsTheProtocolOff() {
        GhosttyTerminal(cols = 20, rows = 10, imageStorageLimitBytes = 0).use { off ->
            off.resize(20, 10, 10, 20)
            off.vtWrite(kitty("a=T,f=32,s=2,v=2,i=1", rgba))

            assertEquals(emptyList(), off.kittyPlacements())
            assertNull(off.kittyImage(1))
        }
    }

    @Test
    fun aQueryIsAnswered() {
        val written = ByteArrayOutputStream()
        terminal.onWritePty = { written.write(it) }

        terminal.vtWrite("\u001b_Ga=q,i=31,s=1,v=1,t=d,f=24;AAAA\u001b\\")

        val answer = written.toString(Charsets.UTF_8)
        assertTrue("i=31;OK" in answer, "The terminal says that it supports the protocol: $answer")
    }

    @Test
    fun aPlacementThatIsOnlyVirtualHasNoPositionToShow() {
        // a=t transmits without placing; U=1 is a virtual placement (unicode placeholders).
        terminal.vtWrite(kitty("a=T,f=32,s=2,v=2,i=1,U=1,c=2,r=2", rgba))

        val placement = placements().singleOrNull()

        assertTrue(placement == null || placement.isVirtual, "Virtual: $placement")
        assertFalse(placement?.visible ?: false)
    }

    @Test
    fun aPlainTerminalHasNoPlacements() {
        terminal.vtWrite("just text\r\n")

        assertEquals(emptyList(), placements())
        assertEquals(0, terminal.kittyGeneration)
    }
}
