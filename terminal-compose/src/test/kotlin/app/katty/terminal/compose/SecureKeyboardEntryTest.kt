package app.katty.terminal.compose

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecureKeyboardEntryTest {
    @Test
    fun theSystemKeepsTheKeysFromOtherProgramsWhileItIsOnAndOnlyThen() {
        // Another program of the system may have it on already, which would make it on whatever this does.
        if (!System.getProperty("os.name").startsWith("Mac") || SecureKeyboardEntry.isEnabled()) return

        assertTrue(SecureKeyboardEntry.enable())
        try {
            assertTrue(SecureKeyboardEntry.isEnabled())
        } finally {
            SecureKeyboardEntry.disable()
        }
        assertFalse(SecureKeyboardEntry.isEnabled())
    }

    @Test
    fun itIsOnAsManyTimesAsItWasEnabled() {
        if (!System.getProperty("os.name").startsWith("Mac") || SecureKeyboardEntry.isEnabled()) return

        SecureKeyboardEntry.enable()
        SecureKeyboardEntry.enable()
        SecureKeyboardEntry.disable()
        try {
            assertTrue(SecureKeyboardEntry.isEnabled(), "One is left")
        } finally {
            SecureKeyboardEntry.disable()
        }
        assertFalse(SecureKeyboardEntry.isEnabled())
    }
}
