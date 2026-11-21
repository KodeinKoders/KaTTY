package app.katty.terminal.compose

/** When the keys that are typed in a terminal are kept from the other programs of the system. */
public enum class TerminalSecureInput {
    /** Never. */
    NEVER,

    /** While the terminal has the keys, and the program is reading a password: it turned the echo off. */
    PASSWORD_PROMPTS,

    /** Whenever the terminal has the keys. */
    ALWAYS,
}
