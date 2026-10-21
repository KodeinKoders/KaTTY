package app.katty.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

class AgentLogosTest {
    @Test
    fun everyAgentHasALogoThatIsDrawn() {
        for (dark in listOf(false, true)) {
            val rendered = render("agent-logos-${if (dark) "dark" else "light"}", 1400, 160, dark = dark) {
                Row(androidx.compose.ui.Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    for (glyph in AgentGlyph.entries) Icon(glyph.icon, glyph.tint, size = 48.dp)
                }
            }
            // Each logo has its own color, and fills a good part of its square: a path that did not parse draws nothing.
            for (glyph in AgentGlyph.entries.filter { it != AgentGlyph.SHELL }) {
                assertTrue(rendered.count((glyph.tint.value shr 32).toInt() and 0xFFFFFF) > 40, "${glyph.name} was not drawn")
            }
        }
    }
}
