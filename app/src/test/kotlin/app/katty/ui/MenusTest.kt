@file:OptIn(ExperimentalTestApi::class)

package app.katty.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.theme.menuStyle

class MenusTest {
    private val calls = mutableListOf<String>()

    private val entries = menu {
        action("Rename terminal") { calls += "rename" }
        divider()
        submenu("Move to workspace") {
            action("release-notes") { calls += "move release-notes" }
            action("api-gateway / rate-limiter") { calls += "move rate-limiter" }
        }
        action("Split right", "⌘D") { calls += "split" }
    }

    @Test
    fun theItemsOfTheMenuWork() = runComposeUiTest {
        var closed = 0
        setContent { KattyTheme(false) { KattyMenu(entries, onDismiss = { closed++ }) } }

        onNodeWithText("Split right").performClick()
        waitForIdle()

        assertEquals(listOf("split"), calls)
        assertTrue(closed >= 1, "The menu was not closed")
    }

    @Test
    fun aSubmenuOpensBesideItsItemAndItsItemsAreChosen() = runComposeUiTest {
        setContent { KattyTheme(false) { KattyMenu(entries, onDismiss = {}) } }

        onNodeWithText("Move to workspace").performMouseInput { moveTo(center) }
        waitForIdle()
        onNodeWithText("release-notes").assertExists()
        onNodeWithText("api-gateway / rate-limiter").performClick()
        waitForIdle()

        assertEquals(listOf("move rate-limiter"), calls)
    }

    // The arrow of an item that opens a submenu is an icon that the theme of the menus names: Jewel draws one that is not found as a gray square,
    // and its own key names an icon of IntelliJ that is in no jar of the application.
    @Test
    fun theArrowOfASubmenuIsAnIconOfTheApplicationInBothThemes() {
        for (dark in listOf(false, true)) {
            var chevron: IconKey? = null
            render("menu-submenu-${if (dark) "dark" else "light"}", 520, 300, dark = dark) {
                chevron = JewelTheme.menuStyle.icons.submenuChevron
                KattyMenu(entries, onDismiss = {})
            }
            val key = assertNotNull(chevron)
            val path = key.path(true)
            assertNotNull(key.iconClass.classLoader.getResource(path), "The arrow of a submenu, $path, is not in the application")
        }
    }
}
