package app.katty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppIconTest {
    @Test
    fun theLogoOfTheRepositoryIsAResourceOfTheApplication() {
        val image = assertNotNull(AppIcon.image(), "logo.png is put in the resources by the build: ${AppIcon.RESOURCE}")

        // Square, so that it is in the middle of the square of the icon, and with a transparent background, so that the square's colour is around it.
        assertEquals(image.width, image.height)
        assertTrue(image.width >= 512, "Big enough for the biggest size of an icon: ${image.width}")
        assertTrue(image.colorModel.hasAlpha())
        assertEquals(0, image.getRGB(0, 0) ushr 24, "The corner is transparent")
    }

    @Test
    fun theIconOfTheApplicationIsTheLogoOnARoundedSquare() {
        val icon = assertNotNull(AppIcon.image(AppIcon.ICON), "The build draws it and puts it in the resources: ${AppIcon.ICON}")

        assertEquals(1024, icon.width)
        assertEquals(1024, icon.height)
        // The square of the grid of macOS icons: 824 pixels in the middle of the canvas, with rounded corners, and nothing around it.
        assertTrue((icon.getRGB(0, 512) ushr 24) < 4, "Around the square: ${Integer.toHexString(icon.getRGB(0, 512))}")
        assertTrue((icon.getRGB(110, 110) ushr 24) < 0x20, "Its rounded corner, where its shadow is: ${Integer.toHexString(icon.getRGB(110, 110))}")
        assertEquals(0xFFFDDDD6.toInt(), icon.getRGB(120, 512), "Its colour, left of the logo")
        assertEquals(0xFFFDDDD6.toInt(), icon.getRGB(512, 120), "Its colour, above the logo")
        // It casts a shadow, as the icons of macOS do in the Dock: black and soft, below it more than on its sides, and less above it.
        val below = icon.getRGB(512, 930)
        assertEquals(0, below and 0xFFFFFF, "The shadow is black: ${Integer.toHexString(below)}")
        assertTrue((below ushr 24) in 0x20..0x50, "The shadow below: ${Integer.toHexString(below)}")
        val side = icon.getRGB(926, 512) ushr 24
        assertTrue(side in 0x18..<(below ushr 24), "The shadow on the side: ${Integer.toHexString(side)}")
        assertTrue((icon.getRGB(512, 96) ushr 24) < side, "The shadow above: ${Integer.toHexString(icon.getRGB(512, 96))}")
        // And it goes as far from it as theirs at the size of the Dock.
        assertTrue((icon.getRGB(512, 984) ushr 24) in 0x06..0x20, "The shadow far below: ${Integer.toHexString(icon.getRGB(512, 984))}")
        // The logo in the middle: the dark bottom of the cat's head.
        val logo = icon.getRGB(512, 700)
        assertEquals(0xFF, logo ushr 24)
        assertTrue((logo shr 16 and 0xFF) < 0x60, "The logo is drawn: ${Integer.toHexString(logo)}")
    }

    @Test
    fun theLogoIsAPainterForTheWindows() {
        assertNotNull(AppIcon.painter())
    }
}
