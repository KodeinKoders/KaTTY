package app.katty

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import app.katty.ui.Katty
import app.katty.ui.OneLine
import org.jetbrains.jewel.intui.window.styling.defaults
import org.jetbrains.jewel.window.DecoratedWindowScope
import org.jetbrains.jewel.window.TitleBar
import org.jetbrains.jewel.window.styling.LocalTitleBarStyle
import org.jetbrains.jewel.window.styling.TitleBarMetrics
import org.jetbrains.jewel.window.styling.TitleBarStyle

/** The height of the title bar of the windows that are not the main one: Jewel's is 40 dp, and these only have a title to show. */
private val SHORT_TITLE_BAR_HEIGHT = 20.dp

/** A title bar with only [title] in it, shorter than Jewel's own, for the windows that are not the main one. */
@Composable
internal fun DecoratedWindowScope.ShortTitleBar(title: String) {
    val style = LocalTitleBarStyle.current
    val short = TitleBarStyle(
        style.colors, TitleBarMetrics.defaults(height = SHORT_TITLE_BAR_HEIGHT), style.icons, style.dropdownStyle, style.iconButtonStyle,
        style.paneButtonStyle, style.paneCloseButtonStyle,
    )
    TitleBar(style = short) { OneLine(title, color = Katty.colors.text) }
}
