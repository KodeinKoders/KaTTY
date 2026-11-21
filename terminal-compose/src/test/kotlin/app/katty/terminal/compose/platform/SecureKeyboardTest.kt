package app.katty.terminal.compose.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecureKeyboardTest {
    @Test
    fun theSystemKeepsTheKeysFromOtherProgramsWhileItIsOnAndOnlyThen() {
        // Another program of the system may have it on already, which would make it on whatever this does.
        if (!System.getProperty("os.name").startsWith("Mac") || secureKeyboard.isEnabled()) return

        assertTrue(secureKeyboard.enable())
        try {
            assertTrue(secureKeyboard.isEnabled())
        } finally {
            secureKeyboard.disable()
        }
        assertFalse(secureKeyboard.isEnabled())
    }

    @Test
    fun itIsOnAsManyTimesAsItWasEnabled() {
        if (!System.getProperty("os.name").startsWith("Mac") || secureKeyboard.isEnabled()) return

        secureKeyboard.enable()
        secureKeyboard.enable()
        secureKeyboard.disable()
        try {
            assertTrue(secureKeyboard.isEnabled(), "One is left")
        } finally {
            secureKeyboard.disable()
        }
        assertFalse(secureKeyboard.isEnabled())
    }
}
