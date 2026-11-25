package app.katty.update

import app.katty.core.AppVersion
import app.katty.core.ManagedUpdates
import app.katty.core.Settings
import app.katty.core.UpdateAction
import app.katty.core.UpdateCheck
import app.katty.core.UpdatePolicy
import app.katty.core.UpdateState
import app.katty.core.UpdateUnavailable
import app.katty.core.updatePolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What the windows show of the updates and what they can do with them: the state of the [Updater], its policy (read when it is shown: the
 * managed preferences are read each time), and the actions of the title bar, the settings, the About window and the palette.
 */
internal class UpdateControls(
    val current: String,
    val state: StateFlow<UpdateState>,
    val policy: () -> UpdatePolicy,
    val onCheck: () -> Unit,
    val onDownload: () -> Unit,
    val onRestart: () -> Unit,
    val onOpen: (URI) -> Unit,
) {
    companion object {
        /** What a test and an application that runs from Gradle have: nothing to update. */
        val NONE = UpdateControls(
            current = "",
            state = MutableStateFlow(UpdateState.Unavailable(UpdateUnavailable.NOT_PACKAGED)),
            policy = { updatePolicy(Settings(), ManagedUpdates.NONE) },
            onCheck = {}, onDownload = {}, onRestart = {}, onOpen = {},
        )
    }
}

/** Whether a look can be asked for in [state]: not while one runs, nor when the application cannot be updated. */
internal fun canCheck(state: UpdateState): Boolean =
    state !is UpdateState.Checking && state !is UpdateState.Downloading && state !is UpdateState.Unavailable

/** The version that the title bar and the palette offer to download or to install, `null` when there is none. */
internal fun offeredVersion(state: UpdateState): AppVersion? = when (state) {
    is UpdateState.Available -> state.manifest.version
    is UpdateState.Ready -> state.version
    else -> null
}

/** What the settings and the About window say of [state], in a sentence. */
internal fun updateStatus(state: UpdateState, action: UpdateAction, zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): String = when (state) {
    UpdateState.Idle -> "Updates have not been checked yet."
    UpdateState.Checking -> "Checking for updates…"
    is UpdateState.UpToDate -> "KaTTY is up to date (checked ${checkedAt(state.checkedAt, zone, now)})."
    is UpdateState.Available -> "KaTTY ${state.manifest.version} is available."
    is UpdateState.Downloading -> "Downloading KaTTY ${state.version}… ${(state.fraction * 100).toInt()}%"
    is UpdateState.Ready ->
        if (action == UpdateAction.INSTALL_ON_QUIT) "KaTTY ${state.version} is ready: it is installed when you quit KaTTY, or restart now."
        else "KaTTY ${state.version} is ready: restart KaTTY to install it."
    is UpdateState.Failed -> "The update failed: ${state.reason}."
    is UpdateState.Unavailable -> when (state.reason) {
        UpdateUnavailable.NOT_PACKAGED -> "Only the installed application updates itself."
        UpdateUnavailable.TRANSLOCATED -> "Move KaTTY to the Applications folder to get updates."
        UpdateUnavailable.READ_ONLY -> "KaTTY cannot replace itself in its folder: download new versions from the releases page."
        UpdateUnavailable.DISABLED_BY_ADMIN -> "Updates are turned off by your organization."
    }
}

private fun checkedAt(at: Instant, zone: ZoneId, now: Instant): String {
    val time = at.atZone(zone)
    return if (time.toLocalDate() == now.atZone(zone).toLocalDate()) "today at ${time.format(DateTimeFormatter.ofPattern("HH:mm"))}"
    else "on ${time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd 'at' HH:mm"))}"
}

/** The short labels of the choices, as the settings and the palette show them. */
internal fun UpdateCheck.label(): String = when (this) {
    UpdateCheck.HOURLY -> "Hourly"
    UpdateCheck.DAILY -> "Daily"
    UpdateCheck.WEEKLY -> "Weekly"
    UpdateCheck.MANUALLY -> "Manually"
}

internal fun UpdateAction.label(): String = when (this) {
    UpdateAction.NOTIFY -> "Notify"
    UpdateAction.DOWNLOAD -> "Download"
    UpdateAction.INSTALL_ON_QUIT -> "Install on quit"
}
