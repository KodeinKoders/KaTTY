package app.katty.screens

import app.katty.ui.uiScaleFactor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.katty.core.LayoutMode
import app.katty.core.Settings
import app.katty.core.CursorShape
import app.katty.core.SecureInput
import app.katty.core.ThemeMode
import app.katty.terminal.themes.ITermColorSchemeInfo
import app.katty.ui.CheckSetting
import app.katty.ui.BelowEnd
import app.katty.ui.Choice
import app.katty.ui.ChoiceOption
import app.katty.ui.Icon
import app.katty.ui.Katty
import app.katty.ui.KattyIcons
import app.katty.ui.KattyMenu
import app.katty.ui.OneLine
import app.katty.ui.SectionLabel
import app.katty.ui.menu
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.component.styling.ScrollbarStyle
import org.jetbrains.jewel.ui.component.styling.ScrollbarVisibility
import org.jetbrains.jewel.ui.theme.scrollbarStyle

/** The width of the main menu. */
private val MAIN_MENU_WIDTH = 264.dp

/** What the main menu leaves of the window's height: the title bar that it opens under, and a margin below it. */
private val MAIN_MENU_MARGIN = 72.dp

/** The least that the main menu is tall, however small the window is. */
private val MAIN_MENU_MIN_HEIGHT = 160.dp

/** How wide the menu's scrollbar is, track and padding included: Jewel's own is 14 dp, which is a lot next to the menu's 264. */
private val MAIN_MENU_SCROLLBAR_WIDTH = 10.dp

/** Jewel's scrollbar style, with a track that is [MAIN_MENU_SCROLLBAR_WIDTH] wide (and the thumb that much less its padding). */
@Composable
private fun narrowScrollbarStyle(): ScrollbarStyle {
    val style = JewelTheme.scrollbarStyle
    return remember(style) {
        val visibility = style.scrollbarVisibility
        val narrow = when (visibility) {
            is ScrollbarVisibility.AlwaysVisible -> ScrollbarVisibility.AlwaysVisible(
                trackThickness = MAIN_MENU_SCROLLBAR_WIDTH,
                trackPadding = visibility.trackPadding,
                trackPaddingWithBorder = visibility.trackPaddingWithBorder,
                trackColorAnimationDuration = visibility.trackColorAnimationDuration,
                expandAnimationDuration = visibility.expandAnimationDuration,
                thumbColorAnimationDuration = visibility.thumbColorAnimationDuration,
                lingerDuration = visibility.lingerDuration,
                scrollbarBackgroundColorLight = visibility.scrollbarBackgroundColorLight,
                scrollbarBackgroundColorDark = visibility.scrollbarBackgroundColorDark,
            )

            is ScrollbarVisibility.WhenScrolling -> ScrollbarVisibility.WhenScrolling(
                trackThickness = MAIN_MENU_SCROLLBAR_WIDTH,
                trackThicknessExpanded = MAIN_MENU_SCROLLBAR_WIDTH,
                trackPadding = visibility.trackPadding,
                trackPaddingWithBorder = visibility.trackPaddingWithBorder,
                trackColorAnimationDuration = visibility.trackColorAnimationDuration,
                expandAnimationDuration = visibility.expandAnimationDuration,
                thumbColorAnimationDuration = visibility.thumbColorAnimationDuration,
                lingerDuration = visibility.lingerDuration,
            )
        }
        ScrollbarStyle(style.colors, style.metrics, style.trackClickBehavior, narrow)
    }
}

/** The name of the font that is bundled, which is what the font family is when the user has not chosen one. */
const val BUNDLED_FONT_NAME = "JetBrains Mono"

/**
 * The main menu: the only place where the application is set up. It opens under the button that [onDismiss] closes it from.
 *
 * @param colorScheme The entry of the default color scheme, for its swatch.
 * @param fontFamilies The monospaced families of the system, which may still be being listed.
 * @param onPickColorScheme Opens the picker of the default color scheme.
 */
@Composable
fun MainMenu(
    settings: Settings,
    onChange: ((Settings) -> Settings) -> Unit,
    colorScheme: ITermColorSchemeInfo?,
    fontFamilies: List<String>,
    onPickColorScheme: () -> Unit,
    onDismiss: () -> Unit,
    onEditShortcuts: () -> Unit = {},
    onAbout: () -> Unit = {},
) {
    val colors = Katty.colors
    Popup(popupPositionProvider = BelowEnd, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        // Never taller than what is left of the window under the title bar: what does not fit is scrolled, as it is when the window is small.
        val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
        Column(
            Modifier.width(MAIN_MENU_WIDTH * uiScaleFactor()).heightIn(max = (windowHeight - MAIN_MENU_MARGIN).coerceAtLeast(MAIN_MENU_MIN_HEIGHT))
                .shadow(16.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)).background(colors.panel)
                .border(1.dp, colors.line, RoundedCornerShape(8.dp)).padding(6.dp).semantics { contentDescription = "Settings" },
        ) {
            val scrollState = rememberScrollState()
            VerticallyScrollableContainer(scrollState, style = narrowScrollbarStyle()) {
                Column(Modifier.verticalScroll(scrollState)) {
                    Section("LAYOUT") {
                        Choice(
                            listOf(ChoiceOption(LayoutMode.AUTO, "Auto"), ChoiceOption(LayoutMode.HORIZONTAL, "Horizontal"), ChoiceOption(LayoutMode.VERTICAL, "Vertical")),
                            settings.layout, { value -> onChange { it.copy(layout = value) } },
                            Modifier.padding(horizontal = 4.dp),
                        )
                        Caption("Auto uses Vertical when the window is taller than it is wide.")
                    }
                    Separator()
                    Section("THEME") {
                        Choice(
                            listOf(ChoiceOption(ThemeMode.LIGHT, "Light"), ChoiceOption(ThemeMode.DARK, "Dark"), ChoiceOption(ThemeMode.SYSTEM, "System")),
                            settings.theme, { value -> onChange { it.copy(theme = value) } },
                            Modifier.padding(horizontal = 4.dp),
                        )
                    }
                    Separator()
                    Section("INTERFACE TEXT SIZE") {
                        UiScaleStepper(settings) { scale -> onChange { it.copy(uiScale = scale) } }
                        Caption("The text of the application, not of the terminals: their font size is below.")
                    }
                    Separator()
                    Section("TERMINAL COLOR SCHEME") {
                        Field(settings.colorScheme, "Terminal color scheme: ${settings.colorScheme}", onPickColorScheme) {
                            if (colorScheme != null) Swatches(colorScheme)
                        }
                    }
                    Separator()
                    Section("CURSOR") {
                        Choice(
                            listOf(ChoiceOption(CursorShape.BLOCK, "Block"), ChoiceOption(CursorShape.BAR, "Bar")),
                            settings.cursorShape, { value -> onChange { it.copy(cursorShape = value) } },
                            Modifier.padding(horizontal = 4.dp),
                        )
                        CheckSetting("Blinking", settings.cursorBlink, { value -> onChange { it.copy(cursorBlink = value) } }, Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                        Caption("The cursor at the prompt and until a program changes it. The shell puts it back at each prompt.")
                    }
                    Separator()
                    Section("KEYBOARD") {
                        Field("Keyboard shortcuts…", "Edit the keyboard shortcuts", onEditShortcuts)
                    }
                    Separator()
                    Section("SECURE KEYBOARD ENTRY") {
                        Choice(
                            listOf(
                                ChoiceOption(SecureInput.NEVER, "Never"),
                                ChoiceOption(SecureInput.PASSWORD_PROMPTS, "Passwords"),
                                ChoiceOption(SecureInput.ALWAYS, "Always"),
                            ),
                            settings.secureInput, { value -> onChange { it.copy(secureInput = value) } },
                            Modifier.padding(horizontal = 4.dp),
                        )
                        Caption("Keeps what is typed in a terminal from the other applications. Text expanders and global shortcuts stop working meanwhile.")
                    }
                    Separator()
                    Section("AGENT USAGE") {
                        CheckSetting("Show agent usage", settings.showUsage, { value -> onChange { it.copy(showUsage = value) } }, Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                        Choice(
                            listOf(ChoiceOption(false, "Panel"), ChoiceOption(true, "Window")),
                            settings.usageInWindow, { value -> onChange { it.copy(usageInWindow = value) } },
                            Modifier.padding(horizontal = 4.dp),
                            enabled = settings.showUsage,
                        )
                        Caption("The window is for another screen. Closing it puts the usage back in the panel.")
                    }
                    Separator()
                    Section("FONT FAMILY") {
                        FontField(settings.fontFamily, fontFamilies) { family -> onChange { it.copy(fontFamily = family) } }
                        FontSizeStepper(settings.fontSize) { size -> onChange { it.copy(fontSize = size) } }
                        CheckSetting("Font ligatures", settings.ligatures, { value -> onChange { it.copy(ligatures = value) } }, Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                        Caption("Shows sequences like => and != as single glyphs.")
                    }
                    Separator()
                    Field("About KaTTY", "About KaTTY", onAbout, chevron = false)
                }
            }
        }
    }
}

@Composable
private fun Section(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionLabel(label, Modifier.padding(start = 10.dp, top = 8.dp, bottom = 4.dp))
        content()
    }
}

@Composable
private fun Separator() {
    Divider(org.jetbrains.jewel.ui.Orientation.Horizontal, Modifier.padding(vertical = 6.dp, horizontal = 4.dp))
}

@Composable
private fun Caption(text: String) {
    Text(text, Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 2.dp), color = Katty.colors.muted, fontSize = 11.sp)
}

/** A field that opens a choice: its value, and a chevron. */
@Composable
internal fun Field(value: String, description: String, onClick: () -> Unit, chevron: Boolean = true, leading: @Composable () -> Unit = {}) {
    val colors = Katty.colors
    Row(
        Modifier.padding(horizontal = 4.dp).fillMaxWidth().heightIn(min = 30.dp).clip(RoundedCornerShape(5.dp)).border(1.dp, colors.line, RoundedCornerShape(5.dp))
            .semantics { contentDescription = description }
            .clickable(role = Role.DropdownList, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leading()
        OneLine(value, Modifier.weight(1f), color = colors.text)
        if (chevron) Icon(KattyIcons.ChevronDown, colors.muted, size = 14.dp)
    }
}

/** Four colors of a scheme side by side: its background and three of its colors. */
@Composable
private fun Swatches(scheme: ITermColorSchemeInfo) {
    val swatch = listOf(scheme.background, scheme.accents[0], scheme.accents[1], scheme.accents[3])
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (color in swatch) Box(Modifier.size(width = 8.dp, height = 14.dp).clip(RoundedCornerShape(2.dp)).background(color.toColor()))
    }
}

@Composable
private fun FontField(family: String?, families: List<String>, onChoose: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = family ?: BUNDLED_FONT_NAME
    Box {
        Field(name, "Font family: $name", { open = true })
        if (open) {
            KattyMenu(
                menu {
                    action("$BUNDLED_FONT_NAME (bundled)") { onChoose(null) }
                    divider()
                    for (candidate in families.filter { it != BUNDLED_FONT_NAME }) action(candidate) { onChoose(candidate) }
                },
                onDismiss = { open = false },
            )
        }
    }
}

/** The size of the font: a stepper by half a pixel, which keeps to the range that can be used. */
@Composable
internal fun FontSizeStepper(size: Float, onChange: (Float) -> Unit) {
    Stepper("Font size", formatSize(size), "font size", { onChange(size - FONT_STEP) }, { onChange(size + FONT_STEP) })
}

/** The size of the text of the application: a stepper through the sizes it can have, as a percentage of the usual one. */
@Composable
internal fun UiScaleStepper(settings: Settings, onChange: (Float) -> Unit) {
    Stepper(
        "Text size", formatScale(settings.effectiveUiScale), "interface text size",
        { onChange(settings.steppedUiScale(-1)) }, { onChange(settings.steppedUiScale(1)) },
        canDecrease = settings.steppedUiScale(-1) != settings.effectiveUiScale,
        canIncrease = settings.steppedUiScale(1) != settings.effectiveUiScale,
    )
}

/**
 * A value with a button on each side that makes it smaller and bigger. [what] is what the buttons say to a screen reader: "Decrease $what".
 * A button that cannot go further is dimmed and does nothing.
 */
@Composable
private fun Stepper(
    label: String, value: String, what: String, onDecrease: () -> Unit, onIncrease: () -> Unit, canDecrease: Boolean = true, canIncrease: Boolean = true,
) {
    val colors = Katty.colors
    Row(Modifier.padding(horizontal = 8.dp).fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).semantics { contentDescription = label }, color = colors.text)
        Row(Modifier.clip(RoundedCornerShape(5.dp)).border(1.dp, colors.line, RoundedCornerShape(5.dp)), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(26.dp).clickable(enabled = canDecrease, role = Role.Button, onClick = onDecrease).semantics { contentDescription = "Decrease $what" },
                contentAlignment = Alignment.Center,
            ) { Text("−", fontSize = 15.sp, color = if (canDecrease) colors.text else colors.muted) }
            Box(Modifier.width(1.dp).height(26.dp).background(colors.line))
            Text(value, Modifier.width(56.dp * uiScaleFactor()), color = colors.text, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Box(Modifier.width(1.dp).height(26.dp).background(colors.line))
            Box(
                Modifier.size(26.dp).clickable(enabled = canIncrease, role = Role.Button, onClick = onIncrease).semantics { contentDescription = "Increase $what" },
                contentAlignment = Alignment.Center,
            ) { Text("+", fontSize = 15.sp, color = if (canIncrease) colors.text else colors.muted) }
        }
    }
}

private const val FONT_STEP = 0.5f

/** The size as the stepper shows it: `12.5 px`, and `13 px` for a whole number. */
internal fun formatSize(size: Float): String = (if (size % 1f == 0f) size.toInt().toString() else size.toString()) + " px"

/** The scale as the stepper shows it: `110%`. */
internal fun formatScale(scale: Float): String = "${kotlin.math.round(scale * 100).toInt()}%"
