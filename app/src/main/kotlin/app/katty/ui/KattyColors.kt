package app.katty.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The colors of the design, light and dark: they are what the screens are drawn with, and the ones that Jewel does not have (the states
 * of an agent) or has a little differently. The terminals do not use them: their colors are the color scheme's.
 */
@Immutable
data class KattyColors(
    /** The window and the area of the splits. */
    val bg: Color,
    /** Panes, menus, dialogs and the header of a workspace. */
    val panel: Color,
    /** The title bar, the sidebar and the headers of panes. */
    val side: Color,
    /** Borders and dividers. */
    val line: Color,
    val text: Color,
    /** Secondary text and icons. */
    val muted: Color,
    /** A selected row or tab. */
    val sel: Color,
    val hover: Color,
    /** The focus ring, the selected underline and the main action. */
    val accent: Color,
    val work: Color,
    val input: Color,
    val done: Color,
    /** Destructive buttons, and the glyph of an agent that failed. */
    val err: Color,
    val idle: Color,
    /** What is drawn on a filled state glyph. */
    val onStatus: Color,
    val dark: Boolean,
) {
    companion object {
        val Light = KattyColors(
            bg = Color(0xFFF2F3F5), panel = Color(0xFFFFFFFF), side = Color(0xFFF7F8FA), line = Color(0xFFDFE1E5),
            text = Color(0xFF1E1F22), muted = Color(0xFF5F6370), sel = Color(0xFFD4E2FF), hover = Color(0xFFE6E8EC),
            accent = Color(0xFF3574F0), work = Color(0xFF2F6BE0), input = Color(0xFFA85F00), done = Color(0xFF1F7A3A),
            err = Color(0xFFC93234), idle = Color(0xFF7A7E8A), onStatus = Color(0xFFFFFFFF), dark = false,
        )
        val Dark = KattyColors(
            bg = Color(0xFF1E1F22), panel = Color(0xFF2B2D30), side = Color(0xFF27282C), line = Color(0xFF393B40),
            text = Color(0xFFDFE1E5), muted = Color(0xFF9DA0A8), sel = Color(0xFF2E436E), hover = Color(0xFF36383D),
            accent = Color(0xFF548AF7), work = Color(0xFF6B9BFA), input = Color(0xFFE5A93D), done = Color(0xFF6CC17D),
            err = Color(0xFFF2767A), idle = Color(0xFF8C909B), onStatus = Color(0xFF1E1F22), dark = true,
        )
    }
}

val LocalKattyColors = staticCompositionLocalOf { KattyColors.Light }

/** Whether the system asks for less motion: what spins does not. */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** The colors of the design for the theme that is in use. */
object Katty {
    val colors: KattyColors
        @androidx.compose.runtime.Composable
        @androidx.compose.runtime.ReadOnlyComposable
        get() = LocalKattyColors.current
}
