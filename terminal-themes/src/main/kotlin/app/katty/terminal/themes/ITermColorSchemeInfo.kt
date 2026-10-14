package app.katty.terminal.themes

import app.katty.terminal.TerminalColor

/**
 * What a list of color schemes shows of one.
 *
 * @param dark Whether the background is dark.
 * @param accents The red, green, yellow, blue, magenta and cyan of the scheme's palette, in that order.
 */
public data class ITermColorSchemeInfo(
    val name: String,
    val dark: Boolean,
    val background: TerminalColor,
    val foreground: TerminalColor,
    val accents: List<TerminalColor>,
)
