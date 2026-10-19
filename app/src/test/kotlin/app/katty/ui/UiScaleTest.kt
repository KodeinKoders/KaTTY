@file:OptIn(ExperimentalTestApi::class)

package app.katty.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import org.jetbrains.jewel.ui.component.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UiScaleTest {
    private fun ComposeUiTestHeight(scale: Float, tag: String, content: @androidx.compose.runtime.Composable () -> Unit): Float {
        var height = 0f
        runComposeUiTest {
            setContent { KattyTheme(false) { UiScale(scale) { content() } } }
            height = onNodeWithTag(tag).getBoundsInRoot().let { it.bottom - it.top }.value
        }
        return height
    }

    @Test
    fun theTextIsAsMuchBiggerAsTheScaleSaysAndNothingInDpIs() {
        val usual = ComposeUiTestHeight(1f, "text") { Text("Agents", Modifier.testTag("text"), fontSize = 20.sp) }
        val bigger = ComposeUiTestHeight(1.5f, "text") { Text("Agents", Modifier.testTag("text"), fontSize = 20.sp) }

        assertTrue(bigger > usual * 1.3f && bigger < usual * 1.7f, "$usual to $bigger")
    }

    @Test
    fun theTextOfAPopupIsScaledToo() {
        val usual = ComposeUiTestHeight(1f, "popup") { Popup { Text("Menu", Modifier.testTag("popup"), fontSize = 20.sp) } }
        val bigger = ComposeUiTestHeight(1.5f, "popup") { Popup { Text("Menu", Modifier.testTag("popup"), fontSize = 20.sp) } }

        assertTrue(bigger > usual * 1.3f, "$usual to $bigger")
    }

    @Test
    fun whatGoesBackToTheUnscaledDensityIsNotScaled() {
        val usual = ComposeUiTestHeight(1f, "terminal") {
            Text("x", Modifier.testTag("terminal"), fontSize = 20.sp)
        }
        val unscaled = ComposeUiTestHeight(1.5f, "terminal") {
            // What a terminal does in a pane.
            CompositionLocalProvider(LocalDensity provides (LocalUnscaledDensity.current ?: LocalDensity.current)) {
                Text("x", Modifier.testTag("terminal"), fontSize = 20.sp)
            }
        }

        assertEquals(usual, unscaled)
    }

    @Test
    fun theUnscaledDensityIsTheOneBeforeTheFirstScale() {
        var seen: Density? = null
        var scaled: Density? = null
        runComposeUiTest {
            setContent {
                val before = LocalDensity.current
                UiScale(2f) {
                    UiScale(2f) {
                        seen = LocalUnscaledDensity.current
                        scaled = LocalDensity.current
                    }
                }
                assertEquals(before, seen, "A scale inside another does not hide the first density")
            }
        }
        assertEquals(seen!!.fontScale * 4, scaled!!.fontScale)
    }
}
