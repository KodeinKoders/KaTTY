package app.katty.screens

import app.katty.AppShortcut
import app.katty.KeyBinding
import app.katty.ShortcutMap
import app.katty.ui.UiScale
import app.katty.ui.render
import java.awt.event.KeyEvent as Awt
import kotlin.test.Test
import kotlin.test.assertTrue

/** The dialog of the shortcuts, drawn in `build/renders` (`shortcuts-*.png`) in both themes and at the biggest text size: look at it. */
class ShortcutsDialogRenderTest {
    private val changed = ShortcutMap.DEFAULT.with(AppShortcut.SPLIT_MAIN, KeyBinding(Awt.VK_E, alt = true)).with(AppShortcut.FIND, null)

    @Test
    fun drawsTheDialogInBothThemesAndAtTheBiggestTextSize() {
        for ((name, dark, scale) in listOf(Triple("light", false, 1f), Triple("dark", true, 1f), Triple("light-175", false, 1.75f))) {
            // As big as a window: the dialog is 820 dp wide and 640 dp high, and wider at the biggest text.
            val rendered = render("shortcuts-$name", 2000, 1400, dark = dark) {
                UiScale(scale) { ShortcutsDialog(changed, onChange = {}, onClose = {}) }
            }
            // The panel of the dialog is drawn.
            assertTrue(rendered.count(if (dark) 0x2B2D30 else 0xFFFFFF) > 20_000, name)
        }
    }
}
