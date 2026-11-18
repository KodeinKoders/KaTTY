package app.katty.screens

import app.katty.core.AiAnswer
import app.katty.ui.UiScale
import app.katty.ui.render
import kotlin.test.Test
import kotlin.test.assertTrue

class CommandRequestDialogTest {
    // Before it asks, while it asks, once it answered, and when the AI command failed, in both themes and at the smallest and biggest text: look at
    // build/renders/command-request-*.png.
    @Test
    fun drawsTheQuestionAndTheCommandOfTheAi() {
        val states = listOf(
            Triple("empty", null as AiAnswer?, false),
            Triple("asking", null, true),
            Triple("answered", AiAnswer.Command("find . -type f -size +100M -mtime -7"), false),
            Triple("failed", AiAnswer.Failed("it ended with the code 1: not logged in"), false),
        )
        for ((dark, err) in listOf(false to 0xC93234, true to 0xF2767A)) {
            for ((suffix, scale) in listOf("100" to 1f, "175" to 1.75f)) {
                val theme = if (dark) "dark" else "light"
                for ((name, answer, asking) in states) {
                    val image = render("command-request-$name-$theme-$suffix", 1400, if (suffix == "175") 1200 else 800, dark = dark) {
                        UiScale(scale) {
                            CommandRequestDialog(
                                "/Users/someone/Code/project", "zsh", answer, asking, note = null, onAsk = {}, onType = {}, onCopy = {}, onClose = {},
                                initialRequest = if (name == "empty") "" else "Find the files over 100 MB changed this week",
                            )
                        }
                    }
                    // Only a failure is in red.
                    val red = image.count(err, tolerance = 40)
                    if (name == "failed") assertTrue(red > 20, "$name, $theme, $suffix") else assertTrue(red < 5, "$name, $theme, $suffix: $red")
                }
            }
        }
    }
}
