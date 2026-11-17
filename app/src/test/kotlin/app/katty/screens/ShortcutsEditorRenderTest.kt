package app.katty.screens

import app.katty.AppShortcut
import app.katty.KeyBinding
import app.katty.ShortcutMap
import app.katty.ui.UiScale
import app.katty.ui.render
import java.awt.event.KeyEvent as Awt
import kotlin.test.Test
import kotlin.test.assertTrue

/** The page of the shortcuts in the settings window, drawn in `build/renders` (`shortcuts-*.png`) in both themes and at the biggest text size: look at it. */
class ShortcutsEditorRenderTest {
    private val changed = ShortcutMap.DEFAULT.with(AppShortcut.SPLIT_MAIN, KeyBinding(Awt.VK_E, alt = true)).with(AppShortcut.FIND, null)

    @Test
    fun drawsTheEditorInBothThemesAndAtTheBiggestTextSize() {
        for ((name, dark, scale) in listOf(Triple("light", false, 1f), Triple("dark", true, 1f), Triple("light-175", false, 1.75f))) {
            // As the settings window shows it.
            val rendered = render("shortcuts-$name", 1640, 1200, dark = dark) {
                UiScale(scale) {
                    SettingsContent(
                        SettingsPage.SHORTCUTS, onPage = {}, settings = app.katty.core.Settings(), onChange = {}, schemes = emptyList(), fontFamilies = emptyList(),
                        shortcuts = changed, onShortcuts = {}, agents = emptySet(), onAbout = {},
                    )
                }
            }
            // The panel of the page is drawn.
            assertTrue(rendered.count(if (dark) 0x2B2D30 else 0xFFFFFF) > 20_000, name)
        }
    }
}
