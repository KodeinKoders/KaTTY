package app.katty.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.darkThemeDefinition
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.styling.dark
import org.jetbrains.jewel.intui.standalone.styling.defaults
import org.jetbrains.jewel.intui.standalone.styling.light
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.intui.window.decoratedWindow
import org.jetbrains.jewel.intui.window.styling.dark
import org.jetbrains.jewel.intui.window.styling.lightWithLightHeader
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.jewel.ui.component.styling.LocalMenuStyle
import org.jetbrains.jewel.ui.component.styling.MenuIcons
import org.jetbrains.jewel.ui.component.styling.MenuStyle
import org.jetbrains.jewel.ui.icon.PathIconKey
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
        styling = ComponentStyling.default().decoratedWindow(titleBarStyle = if (dark) TitleBarStyle.dark() else TitleBarStyle.lightWithLightHeader())
            .provide(LocalMenuStyle provides menuStyle(dark)),
    ) {
        CompositionLocalProvider(
            LocalKattyColors provides if (dark) KattyColors.Dark else KattyColors.Light,
            LocalReducedMotion provides reducedMotion,
            content = content,
        )
    }
}

// The arrow of an item that opens a submenu. Jewel's menus name an icon of IntelliJ for it (`AllIconsKeys.General.ChevronRight`), which is in
// IntelliJ's own jar of icons and in none that Jewel brings: Jewel drew a gray square instead. It is the chevron of the design, which Jewel tints
// in the color of the item, so one file is enough for both themes.
private val SubmenuChevron = PathIconKey("app/katty/icons/chevron-right.svg", KattyIcon::class.java)

/** Jewel's style of the menus, with the arrow of a submenu that the application has ([SubmenuChevron]). */
private fun menuStyle(dark: Boolean): MenuStyle {
    val icons = MenuIcons.defaults(submenuChevron = SubmenuChevron)
    return if (dark) MenuStyle.dark(icons = icons) else MenuStyle.light(icons = icons)
}

