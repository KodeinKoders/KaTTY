package app.katty.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * The density before [UiScale] made the text bigger or smaller, `null` where it did not. What is drawn with a size of its own (a terminal, whose
 * font is a setting) goes back to it: `CompositionLocalProvider(LocalDensity provides (LocalUnscaledDensity.current ?: LocalDensity.current))`.
 */
val LocalUnscaledDensity = staticCompositionLocalOf<Density?> { null }

/**
 * Makes the text of [content] [scale] times as big as it is: the `sp` of every text, and nothing that is in `dp`, so that the layout stays what it
 * is and only what is written grows. It is what the user chooses for the application, for the ones that read better with a bigger text.
 *
 * It has to be in the content of a window: a window gives its own density to what it shows, which hides one that is given outside it.
 * It is always there, even for 1, so that changing the scale does not make what is under it start again.
 */
@Composable
fun UiScale(scale: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalUnscaledDensity provides (LocalUnscaledDensity.current ?: density),
        LocalDensity provides Density(density.density, density.fontScale * scale),
        content = content,
    )
}

/**
 * How much bigger than usual the text is where this is called: 1 when it is not made bigger or smaller. What holds text and has a width of its
 * own (a menu, a panel, a field) takes it: `width(264.dp * uiScaleFactor())`, so that a bigger text does not wrap in a box that was made for the usual one.
 */
@Composable
fun uiScaleFactor(): Float {
    val density = LocalDensity.current
    val unscaled = LocalUnscaledDensity.current ?: return 1f
    return density.fontScale / unscaled.fontScale
}
