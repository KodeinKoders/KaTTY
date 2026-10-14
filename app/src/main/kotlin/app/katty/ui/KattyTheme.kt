package app.katty.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.darkThemeDefinition
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.intui.window.decoratedWindow
import org.jetbrains.jewel.intui.window.styling.dark
import org.jetbrains.jewel.intui.window.styling.lightWithLightHeader
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.jewel.window.styling.TitleBarStyle

/**
 * Jewel's Int UI theme, light or dark, with the colors of the design next to it.
 *
 * The title bar's style is Jewel's, which the decorated window reads: the light one has a light header, as the design draws it.
 */
@Composable
fun KattyTheme(dark: Boolean, reducedMotion: Boolean = false, content: @Composable () -> Unit) {
    IntUiTheme(
        theme = if (dark) JewelTheme.darkThemeDefinition() else JewelTheme.lightThemeDefinition(),
        styling = ComponentStyling.default().decoratedWindow(titleBarStyle = if (dark) TitleBarStyle.dark() else TitleBarStyle.lightWithLightHeader()),
    ) {
        CompositionLocalProvider(
            LocalKattyColors provides if (dark) KattyColors.Dark else KattyColors.Light,
            LocalReducedMotion provides reducedMotion,
            content = content,
        )
    }
}
