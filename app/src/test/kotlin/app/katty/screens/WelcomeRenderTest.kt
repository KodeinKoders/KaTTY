package app.katty.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import app.katty.core.Agent
import app.katty.ui.render
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class WelcomeRenderTest {
    @Test
    fun drawsTheWelcomeWithItsAgentsInBothThemes() {
        for (dark in listOf(false, true)) {
            val rendered = render("welcome-agents-${if (dark) "dark" else "light"}", 1440, 1100, dark = dark) {
                Welcome(onAddProject = {}, modifier = Modifier.fillMaxSize(), detected = { DetectedAgents(mapOf(Agent.CLAUDE to Path.of("/bin/claude"), Agent.COPILOT to Path.of("/bin/copilot"))) })
            }
            // The green of "Detected" is there.
            assertTrue(rendered.count(if (dark) 0x6CC17D else 0x1F7A3A) > 50)
        }
    }
}
