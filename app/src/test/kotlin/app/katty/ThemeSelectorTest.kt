@file:OptIn(ExperimentalComposeUiApi::class)

package app.katty

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ThemeSelectorTest {
    @Test
    fun opensTheMenuOfALongListAndSelectsAnEntry() {
        val names = List(800) { "Theme $it" }
        val chosen = mutableListOf<String>()
        val scene = ImageComposeScene(width = 600, height = 500, density = Density(1f)) {
            MaterialTheme { ThemeSelector(names, selected = "Theme 5", onSelect = { chosen += it }) }
        }
        try {
            var clock = 0L
            fun render() = Bitmap.makeFromImage(scene.render(clock)).also { clock += 16_000_000 }

            val closed = render()
            scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 30f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 30f))
            repeat(5) { render() }
            val open = render()

            // The menu is drawn below the field, where nothing was.
            assertNotEquals(closed.getColor(100, 150), open.getColor(100, 150), "The menu is not open")

            // The first entry is just under the field.
            scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 85f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 85f))
            repeat(5) { render() }
            assertEquals(listOf("Theme 0"), chosen)
        } finally {
            scene.close()
        }
    }
}
