package app.katty.screens

import app.katty.core.AiDiagnosis
import app.katty.core.CommandQuestion
import app.katty.core.CommandRun
import app.katty.ui.UiScale
import app.katty.ui.render
import kotlin.test.Test
import kotlin.test.assertTrue

class CommandAnswerDialogTest {
    private val failure = CommandRun("./gradlew :app:test --tests '*GitTreesTest*'", 1, "")

    // While it asks, once it answered with a correction, and when the AI command failed, in both themes and at the smallest and biggest text: look
    // at build/renders/failure-dialog-*.png.
    @Test
    fun drawsTheAnswerOfTheAi() {
        val explained = AiDiagnosis.Explained(
            "The test task found no test that matches the filter: the class is GitTreesTest in the app module, and the command ran the root project's " +
                "test task, which has no such class.\nRun it on the app module.",
            "./gradlew :app:test --tests '*GitTreesTest*'",
        )
        for ((dark, err) in listOf(false to 0xC93234, true to 0xF2767A)) {
            for ((suffix, scale) in listOf("100" to 1f, "175" to 1.75f)) {
                val theme = if (dark) "dark" else "light"
                for ((name, answer) in listOf("asking" to null, "explained" to explained, "failed" to AiDiagnosis.Failed("it ended with the code 1: not logged in"))) {
                    val image = render("failure-dialog-$name-$theme-$suffix", 1400, if (suffix == "175") 1200 else 800, dark = dark) {
                        UiScale(scale) {
                            CommandAnswerDialog(CommandQuestion.WHY_FAILED, failure, answer, note = null, typeLabel = "Type it", onType = {}, onCopy = {}, onRetry = {}, onClose = {})
                        }
                    }
                    // The exit code is in red.
                    assertTrue(image.count(err, tolerance = 40) > 20, "$name, $theme, $suffix")
                }
                // A command that went well, explained: no correction, and its exit code is not red.
                val explainedOnly = render("command-explained-$theme-$suffix", 1400, if (suffix == "175") 1200 else 800, dark = dark) {
                    UiScale(scale) {
                        CommandAnswerDialog(
                            CommandQuestion.EXPLAIN, CommandRun("git log -1 --stat", 0, ""),
                            AiDiagnosis.Explained("It shows the last commit, with the files it changed and how many lines.", null),
                            note = null, typeLabel = "Type it", onType = {}, onCopy = {}, onRetry = {}, onClose = {},
                        )
                    }
                }
                assertTrue(explainedOnly.count(err, tolerance = 40) < 5, "Nothing is red ($theme, $suffix)")
            }
        }
    }
}
