@file:OptIn(ExperimentalTestApi::class)

package app.katty

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.height
import app.katty.ui.KattyTheme
import app.katty.ui.UiScale
import java.io.File
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AboutWindowTest {
    @Test
    fun theWindowShowsTheLogoTheNameTheVersionAndTheCredits() = runComposeUiTest {
        setContent { KattyTheme(false) { AboutContent() } }

        onNodeWithContentDescription("KaTTY logo").assertIsDisplayed()
        onNodeWithText("KaTTY").assertIsDisplayed()
        onNodeWithText(versionText()).assertIsDisplayed()
        onNodeWithText(commitDateText()!!).assertIsDisplayed()
        onNodeWithText("Made with love by Kodein Koders").assertIsDisplayed()
    }

    @Test
    fun aBuildOfAnotherBranchThanMainSaysWhichOne() = runComposeUiTest {
        var branch by mutableStateOf("feature/rate-limit")
        setContent { KattyTheme(false) { AboutContent(branch = branch) } }

        onNodeWithText("Branch feature/rate-limit").assertIsDisplayed()

        branch = "main"
        onNodeWithText("Branch", substring = true).assertDoesNotExist()
    }

    @Test
    fun theWindowGrowsWithTheTextSoThatNoLineIsCutOrWrapped() = runComposeUiTest {
        val scale = 1.75f
        val size = aboutContentSize(scale)
        setContent { KattyTheme(false) { UiScale(scale) { Box(Modifier.size(size)) { AboutContent(branch = "feature/a-branch-whose-name-is-longer-than-the-window") } } } }

        // A line that does not fit is not pushed out of the column: it is given what height is left, down to none.
        val line = onNodeWithText(versionText()).getUnclippedBoundsInRoot().height
        for (text in listOf("Branch", commitDateText()!!, "Made with love by Kodein Koders")) {
            val bounds = onNodeWithText(text, substring = true).getUnclippedBoundsInRoot()
            assertTrue(bounds.bottom <= size.height, "$text is in the window: $bounds")
            // A branch with a long name is cut short.
            assertTrue(bounds.height > line * 0.75f && bounds.height < line * 1.5f, "$text is one whole line: $bounds")
        }
    }

    @Test
    fun theBranchIsSaidUnlessItIsMain() {
        assertEquals("Branch feature/rate-limit", branchText("feature/rate-limit"))
        assertNull(branchText("main"))
        // A detached HEAD, or a build outside a checkout, has none.
        assertNull(branchText(""))
    }

    @Test
    fun theVersionIsFollowedByTheCommit() {
        assertEquals("Version 1.2.3 (abc1234)", versionText("1.2.3", "abc1234"))
        assertEquals("Version 1.2.3", versionText("1.2.3", "unknown"))
        assertEquals("Version 1.2.3", versionText("1.2.3", ""))
    }

    // The checkout that the test runs in, which the build was made from.
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

    private fun git(vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git") + arguments).directory(root).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        return process.inputStream.readBytes().decodeToString().trim().also { process.waitFor() }
    }

    @Test
    fun theBuildKnowsTheProjectVersionAndTheCommit() {
        val (major, minor, patch) = Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(BuildConfig.VERSION)?.destructured ?: error("Not a version: ${BuildConfig.VERSION}")
        // The build script and the test cannot share a value, so the root script is read: the two cannot drift.
        val declared = Regex("""version = "(\d+\.\d+)\.""").find(File(root, "build.gradle.kts").readText())?.groupValues?.get(1)
        assertEquals(declared, "$major.$minor")
        // The patch is the number of commits that the one it was built from comes after.
        assertEquals((git("rev-list", "--count", "HEAD").toIntOrNull() ?: 1) - 1, patch.toInt())
        assertTrue(Regex("""[0-9a-f]{7,40}|unknown""").matches(BuildConfig.GIT_COMMIT), BuildConfig.GIT_COMMIT)
        // The branch it was built on, none for a detached HEAD.
        assertEquals(git("branch", "--show-current"), BuildConfig.GIT_BRANCH)
        // When the commit was made.
        assertEquals(git("log", "-1", "--format=%cI", "HEAD"), BuildConfig.GIT_COMMIT_DATE)
    }

    @Test
    fun theTimeOfTheCommitIsWrittenInTheUsersZone() {
        val commit = "2026-11-03T14:22:10+01:00"
        assertEquals("Committed: 2026-11-03 14:22", commitDateText(commit, ZoneId.of("Europe/Paris")))
        assertEquals("Committed: 2026-11-03 08:22", commitDateText(commit, ZoneId.of("America/New_York")))
        // The day too is the user's.
        assertEquals("Committed: 2026-11-02 18:30", commitDateText("2026-11-03T00:30:00+01:00", ZoneId.of("America/New_York")))
        // A date that cannot be read, or none (outside a git checkout), is not shown.
        assertNull(commitDateText("unknown", ZoneId.of("Europe/Paris")))
        assertNull(commitDateText("", ZoneId.of("Europe/Paris")))
    }
}
