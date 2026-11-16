package app.katty.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.katty.core.AgentState
import app.katty.core.WorkspaceId
import app.katty.terminals.RunTerminal
import app.katty.ui.Katty
import app.katty.ui.UiScale
import app.katty.ui.render
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunTerminalViewTest {
    @Test
    fun aRunSaysWhetherItRunsSucceededFailedOrCouldNotStart() {
        assertEquals(RunStatus(AgentState.WORKING, "Running"), runStatus(failure = null, exitCode = null))
        assertEquals(RunStatus(AgentState.DONE, "Done"), runStatus(failure = null, exitCode = 0))
        assertEquals(RunStatus(AgentState.ERROR, "Failed (2)"), runStatus(failure = null, exitCode = 2))
        assertEquals(RunStatus(AgentState.ERROR, "Could not start"), runStatus(failure = "No such file", exitCode = null))
    }

    // The question, with the red warning when a command runs, in both themes and at the smallest and biggest text: look at build/renders.
    @Test
    fun drawsTheQuestionOfWhatToRun() {
        for ((dark, err) in listOf(false to 0xC93234, true to 0xF2767A)) {
            for ((suffix, scale) in listOf("100" to 1f, "175" to 1.75f)) {
                val theme = if (dark) "dark" else "light"
                val plain = render("run-dialog-$theme-$suffix", 1400, 700, dark = dark) {
                    UiScale(scale) { RunDialog("./gradlew :app:run", "${System.getProperty("user.home")}/Code/KaTTY", interrupts = false, onRun = { _, _ -> }, onCancel = {}) }
                }
                // An empty field, with an AI to ask: its button is enabled.
                render("run-dialog-ask-$theme-$suffix", 1400, 700, dark = dark) {
                    UiScale(scale) { RunDialog("", "/tmp", interrupts = false, onRun = { _, _ -> }, onCancel = {}, ask = { app.katty.core.AiAnswer.Command("make") }) }
                }
                val warned = render("run-dialog-warning-$theme-$suffix", 1400, 700, dark = dark) {
                    UiScale(scale) { RunDialog("./gradlew :app:run", "/tmp", interrupts = true, onRun = { _, _ -> }, onCancel = {}) }
                }
                assertTrue(warned.count(err, tolerance = 40) > plain.count(err, tolerance = 40) + 50, "The warning is red ($theme, $suffix)")
            }
        }
    }

    // The setting of the AI command, with the default of the installed agent: look at build/renders.
    @Test
    fun drawsTheAiCommandDialog() {
        for (dark in listOf(false, true)) {
            for ((suffix, scale) in listOf("100" to 1f, "175" to 1.75f)) {
                val theme = if (dark) "dark" else "light"
                render("ai-command-dialog-$theme-$suffix", 1600, 800, dark = dark) {
                    UiScale(scale) { AiCommandDialog("claude -p \"\$PROMPT\"", "claude -p \"\$PROMPT\"", onSave = {}, onCancel = {}) }
                }
            }
        }
    }

    // The indicator of the header: a run that runs, one that is shown, one that could not start.
    @Test
    fun drawsTheIndicatorOfARun() {
        val workspace = WorkspaceId("w")
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            for ((name, run) in listOf(
                "running" to RunTerminal(workspace, "./gradlew :app:run", "/tmp", shown = false),
                "shown" to RunTerminal(workspace, "./gradlew :app:run", "/tmp", shown = true),
                "failed" to RunTerminal(workspace, "make", "/tmp", failure = "No such file", shown = false),
            )) {
                val rendered = render("run-indicator-$name-$theme", 700, 80, dark = dark) {
                    Box(Modifier.background(Katty.colors.panel).padding(8.dp)) { RunIndicator(run, onClick = {}) }
                }
                if (name == "failed") assertTrue(rendered.count(if (dark) 0xF2767A else 0xC93234, tolerance = 40) > 20, "A failure is red ($theme)")
            }
        }
    }
}
