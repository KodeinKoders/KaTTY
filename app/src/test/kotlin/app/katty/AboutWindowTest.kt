@file:OptIn(ExperimentalTestApi::class)

package app.katty

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import app.katty.ui.KattyTheme
import java.io.File
import java.time.LocalDate
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AboutWindowTest {
    @Test
    fun theWindowShowsTheLogoTheNameTheVersionAndTheCredits() = runComposeUiTest {
        setContent { KattyTheme(false) { AboutContent() } }

        onNodeWithContentDescription("KaTTY logo").assertIsDisplayed()
        onNodeWithText("KaTTY").assertIsDisplayed()
        onNodeWithText(versionText()).assertIsDisplayed()
        onNodeWithText(buildDateText()!!).assertIsDisplayed()
        onNodeWithText("Made with love by Kodein Koders").assertIsDisplayed()
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
        // The day it was built, which is not after today.
        assertFalse(LocalDate.parse(BuildConfig.BUILD_DATE).isAfter(LocalDate.now()), BuildConfig.BUILD_DATE)
    }

    @Test
    fun theDayOfTheBuildIsWrittenAsTheUserWritesDates() {
        assertEquals("Built on November 3, 2026", buildDateText("2026-11-03", Locale.US))
        assertEquals("Built on 3 novembre 2026", buildDateText("2026-11-03", Locale.FRANCE))
        // A date that cannot be read is not shown.
        assertNull(buildDateText("unknown", Locale.US))
    }
}
