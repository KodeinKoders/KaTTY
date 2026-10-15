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
import kotlin.test.assertTrue

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
}
