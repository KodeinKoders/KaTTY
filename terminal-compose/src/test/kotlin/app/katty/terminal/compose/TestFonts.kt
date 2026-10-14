package app.katty.terminal.compose

/**
 * A font of the system, whatever it is: the tests of the characters that a font lacks use fonts that are not monospaced, which
 * [TerminalFont.system] refuses.
 */
internal fun anySystemFont(name: String): TerminalFont? = TerminalFont.systemUnchecked(name)
