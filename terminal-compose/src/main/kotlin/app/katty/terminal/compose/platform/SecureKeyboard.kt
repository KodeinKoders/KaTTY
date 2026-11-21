package app.katty.terminal.compose.platform

import app.katty.terminal.compose.platform.macos.MacosSecureKeyboard

// What the terminal asks of the system it runs on, which each system does its own way: one implementation for each, in a package of its own
// (`macos`), chosen once by [secureKeyboard]. Nothing outside this package uses an implementation directly (PlatformBoundaryTest checks it).

/**
 * The system's way of keeping what is typed from the other programs (a key logger, a program that listens to the keys of the system), which is
 * what a password needs. The system counts how many times it was enabled: every [enable] must be followed by a [disable].
 */
internal interface SecureKeyboard {
    /** Turns it on, and says whether it worked. Every call has to be undone with [disable]. */
    fun enable(): Boolean

    /** Undoes one [enable]. */
    fun disable()

    /** Whether it is on, for any program of the system. */
    fun isEnabled(): Boolean
}

/** The implementation of the system the terminal runs on: a system that has none never keeps the keys, and says so. */
internal val secureKeyboard: SecureKeyboard = when {
    System.getProperty("os.name").orEmpty().startsWith("Mac") -> MacosSecureKeyboard
    else -> object : SecureKeyboard {
        override fun enable(): Boolean = false

        override fun disable() {}

        override fun isEnabled(): Boolean = false
    }
}
