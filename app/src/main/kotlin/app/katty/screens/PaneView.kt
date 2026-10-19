package app.katty.screens

import androidx.compose.foundation.layout.PaddingValues
import app.katty.ui.uiScaleFactor
import app.katty.AppShortcut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import app.katty.ui.LocalUnscaledDensity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.katty.core.AgentState
import app.katty.core.GitInfo
import app.katty.core.GitKind
import app.katty.core.SplitDirection
import app.katty.core.TerminalSpec
import app.katty.core.terminalIdentity
import app.katty.core.terminals
import app.katty.copyToClipboard
import app.katty.readClipboard
import app.katty.terminal.compose.Terminal
import app.katty.ui.AgentGlyph
import app.katty.ui.AgentTile
import app.katty.ui.ButtonSize
import app.katty.ui.Icon
import app.katty.ui.glyph
import app.katty.ui.onDoubleClick
import app.katty.ui.onSecondaryClick
import app.katty.ui.InlineRename
import app.katty.ui.KattyIconButton
import app.katty.ui.KattyMenu
import app.katty.ui.Katty
import app.katty.ui.KattyIcons
import app.katty.ui.StateGlyph
import app.katty.ui.TileSize
import app.katty.ui.OneLine
import androidx.compose.runtime.withFrameNanos
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/** The height of the header of a pane. */
val PANE_HEADER_HEIGHT = 30.dp

private val PANE_RADIUS = 6.dp

/**
 * The space between a terminal and the edge of its pane, in the terminal's own background color, so that its text is not against the border. The
 * grid is what is left, which is what the program is given: it is in `dp`, so that it does not grow with the text of the application.
 */
internal val TERMINAL_PADDING = 4.dp

/**
 * A terminal in its pane: a header, and the terminal. The focused pane has the accent ring; a click in a terminal, or anything that gives it
 * the keyboard focus, makes it the focused one.
 *
 * @param floating Whether it is the maximized one, over the others: it has the button to put it back.
 * @param terminalSize The size its terminal is laid out at, whatever the pane's: the pane shows what fits. The maximized pane grows from its place
 * to its final bounds, and a terminal that followed it would be resized, and its program with it, on every frame.
 * @param hollow Whether it only holds the place of a terminal that is shown floating: its session has one view only.
 */
@Composable
internal fun PaneView(spec: TerminalSpec, context: PaneContext, floating: Boolean = false, hollow: Boolean = false, terminalSize: DpSize? = null) {
    val colors = Katty.colors
    val focused = context.selected == spec.id
    val handle = context.handles[spec.id]
    val shape = RoundedCornerShape(PANE_RADIUS)
    var contextAt by remember { mutableStateOf<IntOffset?>(null) }
    // The exit code of the shell once it has ended: `null` while it runs.
    val exitCode = handle?.session?.exited?.collectAsState()?.value

    // The focused terminal has the keyboard, unless something else is being typed in. The terminal may not be composed yet.
    LaunchedEffect(handle, focused, context.focusTerminals, context.focusTick, hollow) {
        if (focused && context.focusTerminals && handle != null && !hollow) {
            withFrameNanos { }
            runCatching { handle.focus.requestFocus() }
        }
    }

    // Where the terminal that floats came from is left empty: drawn, it would show as a second pane behind the one that moves.
    if (hollow) {
        Spacer(Modifier.fillMaxSize())
        return
    }

    Column(
        Modifier.fillMaxSize()
            .clip(shape)
            .background(colors.panel)
            // The ring is the border of the design plus the shadow around it: 2 px of the accent, 1 px of the line otherwise.
            .border(if (focused) 2.dp else 1.dp, if (focused) colors.accent else colors.line, shape)
            .onFocusChanged { if (it.hasFocus) context.store.focus(spec.id) }
            // Select all is the terminal's, whenever it has the focus: in a text field of the pane, it is the field's.
            .onPreviewKeyEvent { event ->
                val session = handle?.session ?: return@onPreviewKeyEvent false
                if (event.type != KeyEventType.KeyDown || !event.isMetaPressed || event.isAltPressed || event.isShiftPressed || event.key != Key.A) return@onPreviewKeyEvent false
                session.selectAll()
                true
            },
    ) {
        // What the terminal is called: the name the user gave it, else the agent that reports in it, else the program that runs in it, else
        // its shell. An agent's own name is the one of its tile.
        val info = context.infos[spec.id]
        val report = info?.report
        val process = handle?.processName?.collectAsState()?.value
        val identity = terminalIdentity(spec.name, report, process, handle?.shellName ?: "zsh")
        val title = identity.title
        // The menu of the terminal: from the button of its header, or from a right click anywhere on the header.
        var paneMenu by remember { mutableStateOf(false) }
        var headerMenuAt by remember { mutableStateOf<IntOffset?>(null) }
        val menuEntries = {
            terminalMenu(
                context.actions.destinations,
                spec.colorScheme ?: "Default",
                TerminalMenuActions(
                    colorScheme = { context.actions.onColorScheme(spec.id) },
                    rename = { context.actions.onRename(spec.id) },
                    useProcessName = if (spec.name != null) ({ context.store.renameTerminal(spec.id, null) }) else null,
                    splitRight = { context.store.splitVisually(spec.id, right = true, flipped = context.flipped) },
                    splitDown = { context.store.splitVisually(spec.id, right = false, flipped = context.flipped) },
                    moveTo = { context.actions.onMove(spec.id, it) },
                    copyDirectory = { copyToClipboard(spec.directory) },
                    close = { context.actions.onClose(spec.id) },
                ),
                vertical = context.flipped,
            )
        }
        Box {
            PaneHeader(
                title = title,
                glyph = identity.agent.glyph(),
                state = report?.state ?: AgentState.IDLE,
                stateLabel = if (exitCode != null) "Exited" else null,
                git = handle?.git?.collectAsState()?.value,
                modifier = Modifier.onSecondaryClick { at ->
                    context.store.focus(spec.id)
                    headerMenuAt = IntOffset(at.x.toInt(), at.y.toInt())
                },
                renaming = context.actions.renaming == spec.id,
                onRename = { context.actions.onRename(spec.id) },
                onRenamed = { context.actions.onRenamed(spec.id, it) },
                trailing = {
                    if (floating) {
                        KattyIconButton(KattyIcons.Restore, "Restore pane", { context.store.maximize(null) }, size = ButtonSize.COMPACT, shortcut = AppShortcut.MAXIMIZE.label)
                    } else if (context.workspace.layout.terminals().size > 1) {
                        // With one pane in the workspace there is nothing for it to be maximized over.
                        KattyIconButton(KattyIcons.Maximize, "Maximize pane", { context.store.maximize(spec.id) }, size = ButtonSize.COMPACT, shortcut = AppShortcut.MAXIMIZE.label)
                    }
                    Box {
                        KattyIconButton(KattyIcons.Kebab, "Pane menu", { paneMenu = true }, size = ButtonSize.COMPACT)
                        if (paneMenu) KattyMenu(menuEntries(), onDismiss = { paneMenu = false })
                    }
                },
            )
            headerMenuAt?.let { at -> KattyMenu(menuEntries(), onDismiss = { headerMenuAt = null }, offset = at) }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            val session = handle?.session
            if (session != null) {
                // A shell that was started again is another session: what shows it is made again.
                key(handle.generation) {
                    // The text of the application may be bigger or smaller than usual: the font of a terminal is its own setting.
                    CompositionLocalProvider(LocalDensity provides (LocalUnscaledDensity.current ?: LocalDensity.current)) {
                    Terminal(
                        session,
                        if (terminalSize == null) Modifier.fillMaxSize() else Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(terminalSize),
                        fontSize = context.settings.fontSize.sp,
                        font = context.font,
                        ligatures = context.settings.ligatures,
                        padding = PaddingValues(TERMINAL_PADDING),
                        focusRequester = handle.focus,
                        focusOnStart = false,
                        onContextMenu = { at -> contextAt = IntOffset(at.x.toInt(), at.y.toInt()) },
                    )
                    }
                }
                contextAt?.let { at ->
                    KattyMenu(
                        terminalContextMenu(
                            hasSelection = session.selectedText() != null,
                            actions = ContextMenuActions(
                                copy = { session.selectedText()?.let(::copyToClipboard) },
                                paste = { readClipboard()?.let(session::paste) },
                                selectAll = session::selectAll,
                                find = { context.actions.onFind(spec.id) },
                                clearScreen = session::clearScreen,
                                splitRight = { context.store.splitVisually(spec.id, right = true, flipped = context.flipped) },
                                splitDown = { context.store.splitVisually(spec.id, right = false, flipped = context.flipped) },
                                close = { context.actions.onClose(spec.id) },
                            ),
                            vertical = context.flipped,
                        ),
                        onDismiss = { contextAt = null },
                        offset = at,
                    )
                }
                if (context.actions.finding == spec.id) {
                    FindBar(
                        session,
                        onClose = {
                            context.actions.onFindClosed(spec.id)
                            runCatching { handle.focus.requestFocus() }
                        },
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                    )
                }
            } else if (handle?.failure != null) {
                Text("Could not start the shell: ${handle.failure}", Modifier.padding(12.dp), color = colors.err)
            }
        }
        if (exitCode != null) {
            EndedStrip(exitCode, onReopen = { context.actions.onReopen(spec.id) }, onClose = { context.actions.onClose(spec.id) })
        }
    }
}

/** The bar under a terminal whose shell ended: what happened, and the two things that can be done about it. */
@Composable
internal fun EndedStrip(exitCode: Int, onReopen: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Katty.colors
    Row(
        modifier.fillMaxWidth().background(colors.side).border(1.dp, colors.line).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(KattyIcons.Prompt, colors.muted, size = 14.dp)
        Text("Shell exited with code $exitCode", Modifier.weight(1f), color = colors.text)
        OutlinedButton(onClick = onReopen) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(KattyIcons.Restart, colors.text, size = 14.dp)
                Text("Reopen shell")
            }
        }
        OutlinedButton(onClick = onClose) { Text("Close") }
    }
}

/** What the state of a terminal is called in its header. */
internal fun AgentState.label(): String = when (this) {
    AgentState.IDLE -> "Idle"
    AgentState.WORKING -> "Working"
    AgentState.NEEDS_INPUT, AgentState.ERROR -> "Needs input"
    AgentState.DONE -> "Done"
}

/**
 * The header of a pane: the state, what runs (an agent's tile and name, or the shell's), where it is in git, and the state in words.
 * The name of a terminal that the user renamed is what they chose.
 */
@Composable
internal fun PaneHeader(
    title: String,
    glyph: AgentGlyph,
    state: AgentState,
    git: GitInfo?,
    modifier: Modifier = Modifier,
    /** What the state is called when it is not the usual word: a shell that ended is "Exited". */
    stateLabel: String? = null,
    renaming: Boolean = false,
    onRename: () -> Unit = {},
    onRenamed: (String?) -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    val colors = Katty.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = PANE_HEADER_HEIGHT).background(colors.side).padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StateGlyph(state)
        AgentTile(glyph, size = TileSize.HEADER)
        // What the terminal is and where it is take the space that the state does not.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (renaming) {
                InlineRename(title, onRenamed, Modifier.widthIn(max = 200.dp * uiScaleFactor()))
            } else {
                OneLine(title, Modifier.semantics { contentDescription = "Terminal $title" }.onDoubleClick(onRename), color = colors.text)
            }
            if (git != null) GitLabel(git, Modifier.weight(1f, fill = false))
        }
        Text(
            stateLabel ?: state.label(), color = if (state == AgentState.NEEDS_INPUT || state == AgentState.ERROR) colors.input else colors.muted,
            fontSize = 12.sp, fontWeight = if (state == AgentState.NEEDS_INPUT || state == AgentState.ERROR) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
        )
        trailing()
    }
}
