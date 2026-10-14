package app.katty.terminal.themes

import app.katty.terminal.TerminalColor
import app.katty.terminal.TerminalTheme
import kotlin.math.roundToInt

/**
 * Reads the text of an iTerm2 color scheme, a `.itermcolors` file.
 *
 * Each color is stored as red, green and blue components between 0 and 1, with the color space they are in.
 * They are read as sRGB whatever the color space says, which is exact for most schemes and an approximation
 * for the ones that are in `Calibrated` or `P3`.
 *
 * @throws IllegalArgumentException If the text is not a color scheme: it says what is missing.
 */
public fun parseITermColorScheme(text: String): TerminalTheme {
    val entries = try {
        parsePlist(text).dict.toMap()
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: Exception) {
        throw IllegalArgumentException("Not a property list: ${e.message}", e)
    }

    fun optional(key: String): TerminalColor? = (entries[key] ?: return null).toColor(key)
    fun required(key: String): TerminalColor = optional(key) ?: throw IllegalArgumentException("The color '$key' is missing")

    return TerminalTheme(
        background = required("Background Color"),
        foreground = required("Foreground Color"),
        ansi = List(TerminalTheme.ANSI_COLORS) { required("Ansi $it Color") },
        cursor = optional("Cursor Color"),
        cursorText = optional("Cursor Text Color"),
        bold = optional("Bold Color"),
        selection = optional("Selection Color"),
        selectedText = optional("Selected Text Color"),
    )
}

private fun PlistNode.toColor(key: String): TerminalColor {
    val components = (this as? PlistDict ?: throw IllegalArgumentException("'$key' is not a color")).toMap()

    fun component(name: String): Int {
        val value = (components[name] as? PlistReal)?.value ?: throw IllegalArgumentException("The color '$key' has no '$name'")
        return (value * 255).roundToInt().coerceIn(0, 255)
    }

    return TerminalColor((component("Red Component") shl 16) or (component("Green Component") shl 8) or component("Blue Component"))
}
