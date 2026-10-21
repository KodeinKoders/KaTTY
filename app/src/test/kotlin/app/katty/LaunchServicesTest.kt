package app.katty

import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaunchServicesTest {
    private fun assumeMac() = assumeTrue("Only macOS has LaunchServices", System.getProperty("os.name").startsWith("Mac"))

    @Test
    fun findsAnApplicationThatIsThere() {
        assumeMac()

        assertTrue(applicationInstalled("com.apple.finder"))
    }

    @Test
    fun doesNotFindOneThatIsNot() {
        assumeMac()

        assertFalse(applicationInstalled("net.kodein.katty.not-an-application"))
        assertEquals(false, applicationInstalled(""))
    }
}
