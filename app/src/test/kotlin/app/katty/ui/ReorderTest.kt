@file:OptIn(ExperimentalTestApi::class)

package app.katty.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class ReorderTest {
    @Test
    fun anItemDraggedPastTheNextOneIsMovedAfterItWhenDropped() = runComposeUiTest {
        var order by mutableStateOf(listOf("a", "b", "c"))
        setContent {
            KattyTheme(dark = false) {
                val state = rememberReorderState(order) { key, index -> order = order.toMutableList().also { it.add(index, it.removeAt(it.indexOf(key))) } }
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    for (key in order) {
                        androidx.compose.foundation.layout.Box(
                            Modifier.reorderItem(state, key).fillMaxWidth().height(30.dp).background(Color.Gray)
                                .clickable {}.onDoubleClick {}.onSecondaryClick {}.hoverHighlight().reorderHandle(state, key).semantics { contentDescription = key },
                        )
                    }
                }
            }
        }

        onNodeWithContentDescription("a").performMouseInput {
            press()
            moveBy(Offset(0f, 20f))
            moveBy(Offset(0f, 40f))
            release()
        }
        waitForIdle()
        assertEquals(listOf("b", "a", "c"), order)
    }

    @Test
    fun itemsInARowAreDraggedAlongItsWidth() = runComposeUiTest {
        var order by mutableStateOf(listOf("a", "b", "c"))
        setContent {
            KattyTheme(dark = false) {
                val state = rememberReorderState(order, horizontal = true) { key, index -> order = order.toMutableList().also { it.add(index, it.removeAt(it.indexOf(key))) } }
                androidx.compose.foundation.layout.Row {
                    for (key in order) {
                        androidx.compose.foundation.layout.Box(
                            Modifier.reorderItem(state, key).size(80.dp).background(Color.Gray).reorderHandle(state, key).semantics { contentDescription = key },
                        )
                    }
                }
            }
        }

        // Dragged left, past the one before it.
        onNodeWithContentDescription("c").performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(-20f, 0f))
            moveBy(Offset(-90f, 0f))
            release()
        }
        waitForIdle()
        assertEquals(listOf("a", "c", "b"), order)
    }

}
