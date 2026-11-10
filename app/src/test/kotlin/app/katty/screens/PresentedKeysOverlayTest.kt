package app.katty.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.katty.PresentedKey
import app.katty.PresentedKeys
import app.katty.ui.Katty
import app.katty.ui.render
import kotlin.test.Test
import kotlin.test.assertTrue

class PresentedKeysOverlayTest {
    @Test
    fun drawsTheKeysInBothThemes() {
        for (dark in listOf(false, true)) {
            val keys = PresentedKeys()
            keys.show(PresentedKey("⌘D", "Split right"))
            repeat(4) { keys.show(PresentedKey("↓")) }
            keys.show(PresentedKey("⌃C"))
            val theme = if (dark) "dark" else "light"
            val rendered = render("presented-keys-$theme", 900, 400, dark = dark) {
                Box(Modifier.fillMaxSize().background(Katty.colors.bg)) {
                    PresentedKeysOverlay(keys, Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp))
                }
            }
            // The pill is the same dark one in both themes.
            assertTrue(rendered.count(0x1B1C20, tolerance = 24) > 1_000, "The pills are drawn")
        }
    }
}
