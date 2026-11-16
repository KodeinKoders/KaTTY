@file:OptIn(ExperimentalFoundationApi::class)

package app.katty.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.selectAll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.katty.AppShortcut
import app.katty.KeyBinding
import app.katty.core.AgentState
import app.katty.core.AiAnswer
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import app.katty.core.Settings
import app.katty.terminal.compose.Terminal
import app.katty.terminal.compose.TerminalFont
import app.katty.terminals.RunTerminal
import app.katty.ui.ConfirmDialog
import app.katty.ui.DialogScrim
import app.katty.ui.Katty
import app.katty.ui.ButtonSize
import app.katty.ui.KattyTextButton
import app.katty.ui.KattyIconButton
import app.katty.ui.KattyIcons
import app.katty.ui.LocalUnscaledDensity
import app.katty.ui.OneLine
import app.katty.ui.StateGlyph
import app.katty.ui.hoverHighlight
import app.katty.ui.tooltipText
import app.katty.ui.uiScaleFactor
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip

/** What the window does with the run terminal of a workspace. */
class RunActions(
    /** Asks what to run. */
    val onRun: () -> Unit = {},
    /** Shows it over the panes, or minimizes it. */
    val onToggle: () -> Unit = {},
    /** Stops its command, or closes it once the command has ended: it is gone. */
    val onStop: () -> Unit = {},
    val onMinimize: () -> Unit = {},
    /** Asks the user before a paste that could run commands. */
    val confirmPaste: (suspend (String) -> Boolean)? = null,
    /** Whether its find bar is open, and what closes it. */
    val finding: Boolean = false,
    val onFindClosed: () -> Unit = {},
)

/** What a run says of itself: the state of its glyph and the word for it. */
internal data class RunStatus(val state: AgentState, val label: String)

/**
 * The status of a run whose command [failure] kept from starting, or that ended with [exitCode] (`null` while it runs): a command that ended well is
 * done, one that failed is an error with its code.
 */
internal fun runStatus(failure: String?, exitCode: Int?): RunStatus = when {
    failure != null -> RunStatus(AgentState.ERROR, "Could not start")
    exitCode == null -> RunStatus(AgentState.WORKING, "Running")
    exitCode == 0 -> RunStatus(AgentState.DONE, "Done")
    else -> RunStatus(AgentState.ERROR, "Failed ($exitCode)")
}

@Composable
private fun RunTerminal.status(): RunStatus = runStatus(failure, session?.exited?.collectAsState()?.value)

/** The run of a workspace in the header of the workspace: what it runs and how it is doing. A click shows it over the panes, or minimizes it. */
@Composable
internal fun RunIndicator(run: RunTerminal, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Katty.colors
    val status = run.status()
    val description = if (run.shown) "Minimize the run terminal" else "Show the run terminal"
    Tooltip(tooltip = { Text(tooltipText(description, AppShortcut.RUN_TOGGLE.label)) }) {
        Row(
            modifier.widthIn(max = 280.dp * uiScaleFactor()).clip(RoundedCornerShape(6.dp))
                .background(if (run.shown) colors.sel else Color.Transparent)
                .hoverHighlight(enabled = !run.shown)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = "Run terminal: ${run.command}, ${status.label}" }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StateGlyph(status.state)
            OneLine(run.command, Modifier.weight(1f, fill = false), color = colors.text)
            Text(status.label, color = status.color(), fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
private fun RunStatus.color(): Color = when (state) {
    AgentState.ERROR -> Katty.colors.err
    AgentState.DONE -> Katty.colors.done
    else -> Katty.colors.muted
}

/**
 * The run terminal over the panes of its workspace, which stay under a scrim: a click on the scrim minimizes it. Its header says what it runs, where,
 * and how it is doing, with what stops it (or closes it once its command has ended) and what minimizes it.
 *
 * @param focusTerminals Whether it may take the keyboard: not while something else is being typed in.
 */
@Composable
internal fun RunOverlay(
    run: RunTerminal,
    settings: Settings,
    font: TerminalFont,
    fontSize: Float,
    focusTerminals: Boolean,
    focusTick: Int,
    actions: RunActions,
    modifier: Modifier = Modifier,
) {
    val colors = Katty.colors
    val status = run.status()
    val session = run.session
    val ended = status.state != AgentState.WORKING
    // It has the keyboard when it is shown, and again when what had it is gone.
    LaunchedEffect(run.generation, session, focusTerminals, focusTick) {
        if (focusTerminals && session != null) {
            withFrameNanos { }
            runCatching { run.focus.requestFocus() }
        }
    }
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0E0F12).copy(alpha = .3f)).clickable(interactionSource = null, indication = null, onClick = actions.onMinimize))
        val shape = RoundedCornerShape(8.dp)
        Column(
            Modifier.fillMaxSize().padding(MAXIMIZED_MARGIN).shadow(24.dp, shape).clip(shape).background(colors.panel).border(2.dp, colors.accent, shape)
                // A click in the frame, where nothing takes it, is not one on the scrim.
                .pointerInput(Unit) { detectTapGestures { } }
                .semantics { contentDescription = "Run terminal" },
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = PANE_HEADER_HEIGHT).background(colors.side).padding(start = 10.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StateGlyph(status.state)
                // What it runs and where take the room that is left, whatever their lengths: the state and the buttons are at the right end.
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OneLine(run.command, Modifier.weight(1f, fill = false), color = colors.text)
                    OneLine(homeShortened(run.directory), Modifier.weight(1f, fill = false), color = colors.muted, fontSize = 12.sp)
                }
                Text(status.label, color = status.color(), fontSize = 12.sp, maxLines = 1)
                // At the right of the header, as the buttons of a pane are.
                KattyIconButton(KattyIcons.Minimize, "Minimize the run terminal", actions.onMinimize, size = ButtonSize.COMPACT, shortcut = AppShortcut.RUN_TOGGLE.label)
                if (ended) KattyIconButton(KattyIcons.Close, "Close the run terminal", actions.onStop, size = ButtonSize.COMPACT, shortcut = AppShortcut.CLOSE_TERMINAL.label)
                else KattyIconButton(KattyIcons.Stop, "Stop the run", actions.onStop, size = ButtonSize.COMPACT, tint = colors.err, shortcut = AppShortcut.CLOSE_TERMINAL.label)
            }
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                if (session != null) {
                    // A new run is another session: what shows it is made again.
                    key(run.generation) {
                        // The text of the application may be bigger or smaller than usual: the font of a terminal is its own setting.
                        CompositionLocalProvider(LocalDensity provides (LocalUnscaledDensity.current ?: LocalDensity.current)) {
                            Terminal(
                                session,
                                Modifier.fillMaxSize(),
                                fontSize = fontSize.sp,
                                font = font,
                                ligatures = settings.ligatures,
                                secureInput = settings.secureInput.toTerminal(),
                                optionAsAlt = settings.optionKey.toTerminal(),
                                bell = settings.bell.toTerminal(),
                                copyOnSelect = settings.copyOnSelect,
                                // It runs one command: it has no prompts to mark.
                                commandMarks = false,
                                minimumContrast = settings.minimumContrast.ratio,
                                padding = PaddingValues(TERMINAL_PADDING),
                                focusRequester = run.focus,
                                focusOnStart = false,
                                confirmPaste = actions.confirmPaste,
                            )
                        }
                    }
                    if (actions.finding) {
                        key(run.generation) {
                            FindBar(
                                session,
                                onClose = {
                                    actions.onFindClosed()
                                    runCatching { run.focus.requestFocus() }
                                },
                                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                            )
                        }
                    }
                } else if (run.failure != null) {
                    Text("Could not run the command: ${run.failure}", Modifier.padding(12.dp), color = colors.err)
                }
            }
        }
    }
}

/**
 * Asks what the run terminal of a workspace runs: the field starts with the command that was run last, selected, and Command + Backspace clears it.
 * Where it runs is under it. Enter runs it over the panes, Command + Enter runs it minimized into the header of the workspace, Escape cancels; when a
 * command runs in the workspace, it says in red that it will be stopped.
 *
 * While the field is empty, the AI can be asked for the command ([ask], with its button or [askTick]): its answer is put in the field, to be read
 * before it is run, and why it failed is said in red under it. Closing the question drops an answer that has not come yet.
 *
 * @param onRun Runs the command, minimized or not.
 * @param ask Asks the AI for the command; `null` when there is no AI command.
 * @param askTick A number that changes when the window asks for the AI too (⌘R while the question is open).
 */
@Composable
internal fun RunDialog(
    initial: String,
    directory: String,
    interrupts: Boolean,
    onRun: (command: String, minimized: Boolean) -> Unit,
    onCancel: () -> Unit,
    ask: (suspend () -> AiAnswer)? = null,
    askTick: Int = 0,
) {
    val colors = Katty.colors
    val state = rememberTextFieldState(initial)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        state.edit { selectAll() }
    }
    val run = { minimized: Boolean -> if (state.text.isNotBlank()) onRun(state.text.toString(), minimized) }
    // Asking the AI: only for an empty field, one question at a time, in the scope of this question so that closing it drops the answer.
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val canAsk = ask != null && !asking && state.text.isBlank()
    val askNow = {
        if (ask != null && !asking && state.text.isBlank()) {
            asking = true
            failure = null
            scope.launch {
                val answer = try {
                    ask()
                } finally {
                    asking = false
                }
                when (answer) {
                    is AiAnswer.Command -> {
                        state.setTextAndPlaceCursorAtEnd(answer.line)
                        runCatching { focus.requestFocus() }
                    }
                    is AiAnswer.Failed -> failure = "The AI command failed: ${answer.reason}."
                }
            }
        }
    }
    val firstTick = remember { askTick }
    LaunchedEffect(askTick) { if (askTick != firstTick) askNow() }
    DialogScrim(
        Modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && !event.isMetaPressed) {
                onCancel()
                true
            } else {
                false
            }
        },
        width = 560.dp * uiScaleFactor(),
        onDismiss = onCancel,
    ) {
        Column(Modifier.semantics { contentDescription = "Run a command" }) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Run a command", fontSize = 16.sp, color = colors.text, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(
                        state,
                        Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = "Command" }.onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when {
                                event.key == Key.Enter || event.key == Key.NumPadEnter -> { run(event.isMetaPressed); true }
                                event.key == Key.Backspace && event.isMetaPressed -> { state.clearText(); true }
                                else -> false
                            }
                        },
                        readOnly = asking,
                        placeholder = { Text(if (asking) "Asking the AI…" else "./gradlew run") },
                        textStyle = org.jetbrains.jewel.foundation.theme.JewelTheme.defaultTextStyle.copy(fontFamily = FontFamily.Monospace),
                    )
                    val description = if (ask == null) "No AI command: set one in the settings" else "Ask the AI for the command"
                    Tooltip(tooltip = { Text(tooltipText(description, AppShortcut.RUN.label)) }) {
                        KattyTextButton(if (asking) "Asking…" else "Ask AI", { askNow() }, description = description, enabled = canAsk)
                    }
                }
                OneLine("in ${homeShortened(directory)}", color = colors.muted, fontSize = 12.sp)
                failure?.let { Text(it, color = colors.err) }
                if (interrupts) Text("The command that runs in this workspace will be stopped.", color = colors.err)
            }
            Row(
                Modifier.fillMaxWidth().background(colors.side).padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A key of this question only, which the user does not bind: written as the window writes its keys.
                OneLine("${KeyBinding(java.awt.event.KeyEvent.VK_ENTER).label} runs it minimized", Modifier.weight(1f), color = colors.muted, fontSize = 12.sp)
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
                DefaultButton(onClick = { run(false) }, enabled = state.text.isNotBlank()) { Text("Run") }
            }
        }
    }
}

/** Asked before the command of a run, [running], is stopped, which closes the run: as closing a terminal where a program runs is. */
@Composable
internal fun StopRunConfirmation(running: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    ConfirmDialog(
        title = "Stop the run?",
        message = "“$running” is still running and will be stopped.",
        confirmLabel = "Stop run",
        onConfirm = onConfirm,
        onCancel = onCancel,
    )
}

/** Asked before the command of a run, [running], is stopped to be run again: as restarting a terminal where a program runs is. */
@Composable
internal fun RestartRunConfirmation(running: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    ConfirmDialog(
        title = "Restart the run?",
        message = "“$running” is still running. It will be stopped, and run again.",
        confirmLabel = "Restart run",
        onConfirm = onConfirm,
        onCancel = onCancel,
    )
}

/** Asked before a run stops the command that runs in the workspace, [running], to run [command] in its place. */
@Composable
internal fun StopRunDialog(running: String, command: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    ConfirmDialog(
        title = "Stop the running command?",
        message = "“$running” is still running in this workspace. It will be stopped, and “$command” runs in its place.",
        confirmLabel = "Stop and run",
        onConfirm = onConfirm,
        onCancel = onCancel,
    )
}
