package app.katty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppIconTest {
    @Test
    fun theLogoOfTheRepositoryIsAResourceOfTheApplication() {
        val image = assertNotNull(AppIcon.image(), "logo.png is put in the resources by the build: ${AppIcon.RESOURCE}")

        // Square, so that it is an icon, and with a transparent background, so that it has no white square around it in the Dock.
        assertEquals(image.width, image.height)
        assertTrue(image.width >= 512, "Big enough for the biggest size of an icon: ${image.width}")
        assertTrue(image.colorModel.hasAlpha())
        assertEquals(0, image.getRGB(0, 0) ushr 24, "The corner is transparent")
    }

    @Test
    fun theLogoIsAPainterForTheWindows() {
        assertNotNull(AppIcon.painter())
    }
}
