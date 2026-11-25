@file:OptIn(ExperimentalTestApi::class)

package app.katty.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runSkikoComposeUiTest
import app.katty.ShortcutMap
import app.katty.core.AppVersion
import app.katty.core.KattyState
import app.katty.core.ManagedUpdates
import app.katty.core.Settings
import app.katty.core.UpdateAction
import app.katty.core.UpdateCheck
import app.katty.core.UpdateManifest
import app.katty.core.UpdateState
import app.katty.core.UpdateUnavailable
import app.katty.core.updatePolicy
import app.katty.ui.render
import app.katty.ui.KattyTheme
import app.katty.update.UpdateControls
import app.katty.update.updateStatus
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.jewel.window.TitleBarScope
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpdatesUiTest {
    private val v330 = AppVersion.parse("1.0.330")!!
    private val notes = URI("https://github.com/KodeinKoders/KaTTY/releases/tag/v1.0.330")
    private val manifest = UpdateManifest(v330, URI("https://github.com/x/KaTTY-1.0.330.dmg"), 1, "a".repeat(64), notes)

    private var settings by mutableStateOf(Settings())
    private var managed = ManagedUpdates.NONE
    private val state = MutableStateFlow<UpdateState>(UpdateState.UpToDate(Instant.parse("2026-11-25T13:00:00Z")))
    private val calls = mutableListOf<String>()
    private val controls = UpdateControls(
        current = "1.0.322",
        state = state,
        policy = { updatePolicy(settings, managed) },
        onCheck = { calls += "check" },
        onDownload = { calls += "download" },
        onRestart = { calls += "restart" },
        onOpen = { calls += "open $it" },
    )

    @Test
    fun theStatusSaysWhereTheUpdatesAre() {
        val zone = ZoneOffset.UTC
        val now = Instant.parse("2026-11-25T18:00:00Z")
        fun status(state: UpdateState, action: UpdateAction = UpdateAction.INSTALL_ON_QUIT) = updateStatus(state, action, zone, now)
        assertEquals("KaTTY is up to date (checked today at 13:00).", status(UpdateState.UpToDate(Instant.parse("2026-11-25T13:00:00Z"))))
        assertEquals("KaTTY is up to date (checked on 2026-11-24 at 09:30).", status(UpdateState.UpToDate(Instant.parse("2026-11-24T09:30:00Z"))))
        assertEquals("KaTTY 1.0.330 is available.", status(UpdateState.Available(manifest)))
        assertEquals("Downloading KaTTY 1.0.330… 42%", status(UpdateState.Downloading(v330, .42f)))
        assertEquals("KaTTY 1.0.330 is ready: it is installed when you quit KaTTY, or restart now.", status(UpdateState.Ready(v330, notes)))
        assertEquals("KaTTY 1.0.330 is ready: restart KaTTY to install it.", status(UpdateState.Ready(v330, notes), UpdateAction.DOWNLOAD))
        assertEquals("The update failed: the disk image is not signed by Kodein.", status(UpdateState.Failed("the disk image is not signed by Kodein")))
        assertEquals("Move KaTTY to the Applications folder to get updates.", status(UpdateState.Unavailable(UpdateUnavailable.TRANSLOCATED)))
        assertEquals("Updates are turned off by your organization.", status(UpdateState.Unavailable(UpdateUnavailable.DISABLED_BY_ADMIN)))
    }

    private fun ComposeUiTest.showUpdates() {
        setContent {
            KattyTheme(dark = false) {
                SettingsContent(
                    page = SettingsPage.UPDATES, onPage = {}, settings = settings, onChange = { change -> settings = change(settings) },
                    schemes = emptyList(), fontFamilies = emptyList(), shortcuts = ShortcutMap.DEFAULT, onShortcuts = {}, agents = emptySet(), onAbout = {},
                    updates = controls,
                )
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.choose(label: String) {
        onAllNodesWithText(label)[0].performScrollTo().performClick()
        waitForIdle()
    }

    @Test
    fun theFrequencyAndTheActionAreChosen() = runSkikoComposeUiTest(size = Size(1200f, 1400f)) {
        showUpdates()
        onNodeWithText("KaTTY 1.0.322").assertExists()
        choose("Weekly")
        assertEquals(UpdateCheck.WEEKLY, settings.updateCheck)
        choose("Notify")
        assertEquals(UpdateAction.NOTIFY, settings.updateAction)
        onNodeWithText("Check now").performClick()
        assertEquals(listOf("check"), calls)
    }

    @Test
    fun whatTheOrganizationImposesCannotBeChanged() = runSkikoComposeUiTest(size = Size(1200f, 1400f)) {
        managed = ManagedUpdates(check = UpdateCheck.MANUALLY)
        showUpdates()
        assertEquals(1, onAllNodesWithText("Set by your organization.").fetchSemanticsNodes().size)
        onNode(hasText("Weekly") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Role)).assertIsNotEnabled()
        onNode(hasText("Notify") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Role)).assertIsEnabled()
    }

    @Test
    fun aVersionThatWasFoundIsDownloadedAndOneThatIsReadyRestarts() = runSkikoComposeUiTest(size = Size(1200f, 1400f)) {
        state.value = UpdateState.Available(manifest)
        showUpdates()
        onNodeWithText("Download now").performClick()
        onNodeWithText("Release notes").performClick()
        state.value = UpdateState.Ready(v330, notes)
        waitForIdle()
        onNodeWithText("Restart to update").performClick()
        assertEquals(listOf("download", "open $notes", "restart"), calls)
    }

    @Test
    fun noLookIsAskedForWhileOneRuns() = runSkikoComposeUiTest(size = Size(1200f, 1400f)) {
        state.value = UpdateState.Downloading(v330, .5f)
        showUpdates()
        onNodeWithText("Check now").assertIsNotEnabled()
        onNodeWithContentDescription("Update status").assertExists()
    }

    @Test
    fun aCopyThatCannotUpdateItselfOffersTheReleasesPage() = runSkikoComposeUiTest(size = Size(1200f, 1400f)) {
        state.value = UpdateState.Unavailable(UpdateUnavailable.TRANSLOCATED, URI("https://github.com/KodeinKoders/KaTTY/releases/latest"))
        showUpdates()
        onNodeWithText("Releases page").performClick()
        assertEquals(listOf("open https://github.com/KodeinKoders/KaTTY/releases/latest"), calls)
    }

    private fun handlers(
        checkForUpdates: (() -> Unit)? = null,
        updateVersion: String? = null,
        downloadUpdate: (() -> Unit)? = null,
        restartToUpdate: (() -> Unit)? = null,
        setUpdateCheck: ((UpdateCheck) -> Unit)? = null,
        setUpdateAction: ((UpdateAction) -> Unit)? = null,
    ) = PaletteHandlers(
        addProject = {}, newWorkspace = {}, renameWorkspace = {}, closeWorkspace = {}, splitRight = {}, splitDown = {}, renameTerminal = {},
        closeTerminal = {}, restartTerminal = {}, find = {}, clearScreen = {}, maximize = {}, colorScheme = {}, toggleSidebar = {}, showView = {},
        setLayout = {}, setTheme = {}, jumpToWaiting = {}, markAllSeen = {}, openBoardWindow = {}, openTerminal = {}, openWorkspace = {},
        checkForUpdates = checkForUpdates, updateVersion = updateVersion, downloadUpdate = downloadUpdate, restartToUpdate = restartToUpdate,
        setUpdateCheck = setUpdateCheck, setUpdateAction = setUpdateAction,
    )

    private fun labels(handlers: PaletteHandlers) = paletteCommands(KattyState(), emptyMap(), { "zsh" }, handlers).entries.map { it.label }

    @Test
    fun thePaletteOffersWhatCanBeDoneWithTheUpdates() {
        assertTrue(labels(handlers()).none { "update" in it.lowercase() }, "Nothing when the application cannot update itself")
        val all = labels(handlers(checkForUpdates = {}, updateVersion = "1.0.330", restartToUpdate = {}, setUpdateCheck = {}, setUpdateAction = {}))
        assertTrue("Check for updates" in all)
        assertTrue("Restart to update to KaTTY 1.0.330" in all)
        assertTrue("Download KaTTY 1.0.330" !in all)
        assertTrue("Check for updates: weekly" in all)
        assertTrue("When an update is found: install on quit" in all)
        assertTrue("Download KaTTY 1.0.330" in labels(handlers(updateVersion = "1.0.330", downloadUpdate = {})))
    }

    private object PlainTitleBar : TitleBarScope {
        override val title = "KaTTY"
        override val icon: Painter? = null
        override fun Modifier.align(alignment: Alignment.Horizontal): Modifier = this
    }

    @Test
    fun theTitleBarOffersTheUpdate() = runSkikoComposeUiTest(size = Size(1400f, 200f)) {
        var update by mutableStateOf<TitleBarUpdate?>(null)
        setContent {
            KattyTheme(dark = false) { Row { with(PlainTitleBar) { TitleBarContent(KattyState(), {}, update = update) } } }
        }
        onAllNodesWithText("Restart to update").assertCountEquals(0)
        update = TitleBarUpdate("Restart to update", "Restart KaTTY to install 1.0.330") { calls += "restart" }
        waitForIdle()
        onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button) and hasText("Restart to update")).performClick()
        assertEquals(listOf("restart"), calls)
    }

    @Test
    fun drawsTheUpdatesPageAndTheButtonInBothThemes() {
        state.value = UpdateState.Ready(v330, notes)
        managed = ManagedUpdates(check = UpdateCheck.DAILY)
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            render("settings-updates-$theme", 1640, 1200, dark = dark) {
                SettingsContent(
                    SettingsPage.UPDATES, {}, settings, {}, emptyList(), emptyList(), ShortcutMap.DEFAULT, {}, emptySet(), {}, updates = controls,
                )
            }
            render("titlebar-update-$theme", 1400, 90, dark = dark) {
                Row { with(PlainTitleBar) { TitleBarContent(KattyState(), {}, update = TitleBarUpdate("Restart to update", "Restart") {}) } }
            }
        }
    }
}
