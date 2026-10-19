@file:OptIn(ExperimentalTestApi::class)

package app.katty

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import app.katty.ui.KattyTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AboutWindowTest {
    @Test
    fun theWindowShowsTheLogoTheNameTheVersionAndTheCredits() = runComposeUiTest {
        setContent { KattyTheme(false) { AboutContent() } }

        onNodeWithContentDescription("KaTTY logo").assertIsDisplayed()
        onNodeWithText("KaTTY").assertIsDisplayed()
        onNodeWithText(versionText()).assertIsDisplayed()
        onNodeWithText("Made with love by Kodein Koders").assertIsDisplayed()
    }

    @Test
    fun theVersionIsFollowedByTheCommit() {
        assertEquals("Version 1.2.3 (abc1234)", versionText("1.2.3", "abc1234"))
        assertEquals("Version 1.2.3", versionText("1.2.3", "unknown"))
        assertEquals("Version 1.2.3", versionText("1.2.3", ""))
    }

    @Test
    fun theBuildKnowsTheProjectVersionAndTheCommit() {
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(BuildConfig.VERSION), BuildConfig.VERSION)
        // The build script and the test cannot share a value, so the root script is read: the two cannot drift.
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val declared = Regex("""version = "([^"]+)"""").find(File(root, "build.gradle.kts").readText())?.groupValues?.get(1)
        assertEquals(declared, BuildConfig.VERSION)
        assertTrue(Regex("""[0-9a-f]{7,40}|unknown""").matches(BuildConfig.GIT_COMMIT), BuildConfig.GIT_COMMIT)
    }
}
