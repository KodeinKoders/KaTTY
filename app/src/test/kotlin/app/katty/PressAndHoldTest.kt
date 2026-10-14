package app.katty

import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

class PressAndHoldTest {
    @Test
    fun registersThatHoldingAKeyRepeatsIt() {
        assumeTrue("Only macOS has this setting", System.getProperty("os.name").startsWith("Mac"))

        disablePressAndHold()

        // What is registered, not what is in effect: a value that the user set wins over it.
        assertEquals(false, Objc().use { it.registeredDefault("ApplePressAndHoldEnabled") })
    }

    @Test
    fun registersNothingForAKeyThatWasNotRegistered() {
        assumeTrue("Only macOS has this setting", System.getProperty("os.name").startsWith("Mac"))

        assertEquals(null, Objc().use { it.registeredDefault("KaTTYSettingThatIsNotRegistered") })
    }
}
