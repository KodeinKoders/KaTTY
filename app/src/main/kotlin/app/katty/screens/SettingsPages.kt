package app.katty.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.katty.AppShortcut
import app.katty.ShortcutMap
import app.katty.core.Agent
import app.katty.core.BellMode
import app.katty.core.CursorShape
import app.katty.core.EnvironmentListing
import app.katty.core.Environments
import app.katty.core.GitChangesDisplay
import app.katty.core.LayoutMode
import app.katty.core.MinimumContrast
import app.katty.core.OptionKey
import app.katty.core.ScrollbackSize
import app.katty.core.SecureInput
import app.katty.core.Settings
import app.katty.core.ThemeMode
import app.katty.core.aiAgents
import app.katty.core.defaultAiCommand
import app.katty.terminal.themes.ITermColorSchemeInfo
import app.katty.ui.CheckSetting
import app.katty.ui.Choice
import app.katty.ui.ChoiceOption
import app.katty.ui.Icon
import app.katty.ui.Katty
import app.katty.ui.KattyIconButton
import app.katty.ui.KattyIcons
import app.katty.ui.KattyMenu
import app.katty.ui.KattyTextButton
import app.katty.ui.ListKey
import app.katty.ui.OneLine
import app.katty.ui.SectionLabel
import app.katty.ui.hoverHighlight
import app.katty.ui.listKey
import app.katty.ui.menu
import app.katty.ui.stepped
import app.katty.ui.uiScaleFactor
import kotlinx.coroutines.flow.drop
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer

/** The name of the font that is bundled, which is what the font family is when the user has not chosen one. */
const val BUNDLED_FONT_NAME = "JetBrains Mono"

/** The width of the list of the pages, on the left of the settings window. */
private val PAGE_LIST_WIDTH = 180.dp

/** The widest that the settings of a page are: the window may be much wider, and a choice as wide as it is hard to read. */
private val PAGE_MAX_WIDTH = 560.dp

/** The pages of the settings window, in the order of its list. */
enum class SettingsPage(val title: String) {
    APPEARANCE("Appearance"),
    TERMINAL("Terminal"),
    KEYBOARD("Keyboard"),
    SHORTCUTS("Shortcuts"),
    AGENTS("Agents"),
    ENVIRONMENTS("Environments"),
}

/**
 * What the Environments page does that is not a setting: [onRefresh] reads the folders again (when the page opens), [onAddFolder] asks for a folder
 * to add, [onOpenFolder] shows one in the Finder (made if it is not there), and [onApplyDefault] gives the default environment to the
 * [projectsWithoutEnvironment] projects that have none.
 */
internal class EnvironmentActions(
    val projectsWithoutEnvironment: Int = 0,
    val onRefresh: () -> Unit = {},
    val onAddFolder: () -> Unit = {},
    val onOpenFolder: (String) -> Unit = {},
    val onApplyDefault: () -> Unit = {},
)

/**
 * What the AI command setting is for what is typed in its field: `null`, the default, for the default itself, so that a default that changes
 * (another agent installed) is followed; `""`, the AI turned off, for an empty field while there is a default (with none, `null` already asks
 * nothing, and an agent installed later is offered).
 */
internal fun aiCommandSetting(text: String, default: String?): String? {
    val command = text.trim()
    return when {
        command == default -> null
        command.isEmpty() -> if (default == null) null else ""
        else -> command
    }
}

/**
 * The settings window's content: the list of the pages on the left, the [page] that is shown on the right. It is the only place where the
 * application is set up; every change applies at once, through [onChange].
 *
 * The color scheme of a theme is chosen in the picker, which opens over this content.
 *
 * @param schemes The color schemes that the terminals can have.
 * @param fontFamilies The monospaced families of the system, which may still be being listed.
 * @param shortcuts The shortcuts as they are bound, changed through [onShortcuts].
 * @param agents The agents that are installed: the AI command can be the one of any of them, and is the one of the first while the setting is `null`.
 * @param environments The environments found in the folders of the settings.
 */
@Composable
internal fun SettingsContent(
    page: SettingsPage,
    onPage: (SettingsPage) -> Unit,
    settings: Settings,
    onChange: ((Settings) -> Settings) -> Unit,
    schemes: List<ITermColorSchemeInfo>,
    fontFamilies: List<String>,
    shortcuts: ShortcutMap,
    onShortcuts: (ShortcutMap) -> Unit,
    agents: Set<Agent>,
    onAbout: () -> Unit,
    modifier: Modifier = Modifier,
    environments: EnvironmentListing = EnvironmentListing.EMPTY,
    environmentActions: EnvironmentActions = EnvironmentActions(),
    overlay: @Composable () -> Unit = {},
) {
    val colors = Katty.colors
    // The theme whose color scheme is being chosen: `true` for the dark one.
    var picking by remember { mutableStateOf<Boolean?>(null) }
    Box(modifier.fillMaxSize().background(colors.panel).semantics { contentDescription = "Settings" }) {
        Row(Modifier.fillMaxSize()) {
            PageList(page, onPage, onAbout)
            Box(Modifier.width(1.dp).fillMaxHeight().background(colors.line))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (page) {
                    SettingsPage.SHORTCUTS -> Column(Modifier.fillMaxSize()) {
                        PageTitle(page, Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 12.dp))
                        ShortcutsEditor(shortcuts, onShortcuts, Modifier.weight(1f).fillMaxWidth())
                    }
                    else -> ScrolledPage(page) {
                        when (page) {
                            SettingsPage.APPEARANCE -> AppearancePage(settings, onChange, schemes, fontFamilies, onPickColorScheme = { picking = it })
                            SettingsPage.TERMINAL -> TerminalPage(settings, onChange)
                            SettingsPage.KEYBOARD -> KeyboardPage(settings, onChange)
                            SettingsPage.AGENTS -> AgentsPage(settings, onChange, agents)
                            SettingsPage.ENVIRONMENTS -> EnvironmentsPage(settings, onChange, environments, environmentActions)
                            SettingsPage.SHORTCUTS -> Unit
                        }
                    }
                }
            }
        }
        picking?.let { dark ->
            ColorSchemePicker(
                entries = schemes,
                selected = settings.colorScheme(dark),
                mode = dark,
                onMode = { picking = it },
                onSelect = { name ->
                    if (name != null) onChange { if (dark) it.copy(darkColorScheme = name) else it.copy(lightColorScheme = name) }
                },
                onClose = { picking = null },
            )
        }
        // A question that what is done on a page asks (the default environment applied), over the whole window.
        overlay()
    }
}

/** The list of the pages: a click, or the arrows while it has the focus, shows one. */
@Composable
private fun PageList(page: SettingsPage, onPage: (SettingsPage) -> Unit, onAbout: () -> Unit) {
    val colors = Katty.colors
    val pages = SettingsPage.entries
    Column(
        Modifier.width(PAGE_LIST_WIDTH * uiScaleFactor()).fillMaxHeight().background(colors.side).padding(8.dp)
            .semantics { contentDescription = "Settings pages" }
            .onKeyEvent { event ->
                when (event.listKey()) {
                    ListKey.DOWN -> { onPage(pages[page.ordinal.stepped(1, pages.size)]); true }
                    ListKey.UP -> { onPage(pages[page.ordinal.stepped(-1, pages.size)]); true }
                    else -> false
                }
            }
            .focusable(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (entry in pages) {
            val chosen = entry == page
            Box(
                Modifier.fillMaxWidth().heightIn(min = 30.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (chosen) colors.sel else androidx.compose.ui.graphics.Color.Transparent)
                    .semantics { role = Role.Tab; selected = chosen }
                    .clickable { onPage(entry) }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                OneLine(entry.title, color = colors.text)
            }
        }
        // At the bottom of the column, discreet: the About window, as the application menu's About item and the palette open it.
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 26.dp).clip(RoundedCornerShape(6.dp))
                .hoverHighlight()
                .clickable(role = Role.Button, onClick = onAbout)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(KattyIcons.Info, colors.muted, size = 14.dp * uiScaleFactor())
            OneLine("About KaTTY", color = colors.muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PageTitle(page: SettingsPage, modifier: Modifier = Modifier) {
    Text(page.title, modifier, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Katty.colors.text)
}

/** A page that scrolls when the window is shorter than it, its settings no wider than [PAGE_MAX_WIDTH]. */
@Composable
private fun ScrolledPage(page: SettingsPage, content: @Composable () -> Unit) {
    // Each page starts at its top.
    val scrollState = remember(page) { androidx.compose.foundation.ScrollState(0) }
    VerticallyScrollableContainer(scrollState, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().verticalScroll(scrollState).padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 24.dp)) {
            PageTitle(page, Modifier.padding(bottom = 4.dp))
            Column(Modifier.widthIn(max = PAGE_MAX_WIDTH * uiScaleFactor())) { content() }
        }
    }
}

@Composable
private fun AppearancePage(
    settings: Settings,
    onChange: ((Settings) -> Settings) -> Unit,
    schemes: List<ITermColorSchemeInfo>,
    fontFamilies: List<String>,
    onPickColorScheme: (dark: Boolean) -> Unit,
) {
    Section("LAYOUT") {
        Choice(
            listOf(ChoiceOption(LayoutMode.AUTO, "Auto"), ChoiceOption(LayoutMode.HORIZONTAL, "Horizontal"), ChoiceOption(LayoutMode.VERTICAL, "Vertical")),
            settings.layout, { value -> onChange { it.copy(layout = value) } },
            Modifier.padding(horizontal = 4.dp),
        )
        Caption("Auto uses Vertical when the window is taller than it is wide.")
    }
    Separator()
    Section("THEME") {
        Choice(
            listOf(ChoiceOption(ThemeMode.LIGHT, "Light"), ChoiceOption(ThemeMode.DARK, "Dark"), ChoiceOption(ThemeMode.SYSTEM, "System")),
            settings.theme, { value -> onChange { it.copy(theme = value) } },
            Modifier.padding(horizontal = 4.dp),
        )
    }
    Separator()
    Section("GIT CHANGES") {
        Choice(
            listOf(ChoiceOption(GitChangesDisplay.DOT, "Dot"), ChoiceOption(GitChangesDisplay.COUNTS, "Counts")),
            settings.gitChanges, { value -> onChange { it.copy(gitChanges = value) } },
            Modifier.padding(horizontal = 4.dp),
        )
        Caption("How the branch of a terminal or a workspace says that it has changes that are not committed: a dot, or how many files are staged (+), modified (~), untracked (?) and in conflict (!).")
    }
    Separator()
    Section("INTERFACE TEXT SIZE") {
        UiScaleStepper(settings) { scale -> onChange { it.copy(uiScale = scale) } }
        Caption("The text of the application, not of the terminals: their font size is below.")
    }
    Separator()
    Section("TERMINAL COLOR SCHEME") {
        SchemeField("Light", settings.lightColorScheme, schemes.firstOrNull { it.name == settings.lightColorScheme }) { onPickColorScheme(false) }
        SchemeField("Dark", settings.darkColorScheme, schemes.firstOrNull { it.name == settings.darkColorScheme }) { onPickColorScheme(true) }
        Caption("The terminals that have no scheme of their own follow the theme of the application.")
    }
    Separator()
    Section("FONT FAMILY") {
        FontField(settings.fontFamily, fontFamilies) { family -> onChange { it.copy(fontFamily = family) } }
        FontSizeStepper(settings.fontSize) { size -> onChange { it.copy(fontSize = size) } }
        CheckSetting("Font ligatures", settings.ligatures, { value -> onChange { it.copy(ligatures = value) } }, Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        Caption("Shows sequences like => and != as single glyphs.")
    }
}

@Composable
private fun TerminalPage(settings: Settings, onChange: ((Settings) -> Settings) -> Unit) {
    Section("CURSOR") {
        Choice(
            listOf(ChoiceOption(CursorShape.BLOCK, "Block"), ChoiceOption(CursorShape.BAR, "Bar"), ChoiceOption(CursorShape.UNDERLINE, "Underline")),
            settings.cursorShape, { value -> onChange { it.copy(cursorShape = value) } },
            Modifier.padding(horizontal = 4.dp),
        )
        CheckSetting("Blinking", settings.cursorBlink, { value -> onChange { it.copy(cursorBlink = value) } }, Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        Caption("The cursor at the prompt and until a program changes it. The shell puts it back at each prompt.")
    }
    Separator()
    Section("BELL") {
        Choice(
            listOf(
                ChoiceOption(BellMode.OFF, "Off"),
                ChoiceOption(BellMode.VISUAL, "Visual"),
                ChoiceOption(BellMode.SOUND, "Sound"),
                ChoiceOption(BellMode.VISUAL_AND_SOUND, "Both"),
            ),
            settings.bell, { value -> onChange { it.copy(bell = value) } },
            Modifier.padding(horizontal = 4.dp),
        )
    }
    Separator()
    Section("TEXT") {
        CheckSetting("Bold text uses bright colors", settings.boldIsBright, { value -> onChange { it.copy(boldIsBright = value) } }, Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        Caption("Minimum contrast: text that is hard to read is moved towards white or black.")
        Choice(
            listOf(
                ChoiceOption(MinimumContrast.OFF, "Off"),
                ChoiceOption(MinimumContrast.LOW, "3"),
                ChoiceOption(MinimumContrast.NORMAL, "4.5"),
                ChoiceOption(MinimumContrast.HIGH, "7"),
            ),
            settings.minimumContrast, { value -> onChange { it.copy(minimumContrast = value) } },
            Modifier.padding(horizontal = 4.dp),
        )
    }
    Separator()
    Section("SCROLLBACK (MB)") {
        Choice(
            ScrollbackSize.entries.map { ChoiceOption(it, "${it.megabytes}") },
            settings.scrollback, { value -> onChange { it.copy(scrollback = value) } },
            Modifier.padding(horizontal = 4.dp),
        )
        Caption("How much history a terminal keeps. Lowering it deletes the older lines of the open terminals.")
    }
    Separator()
    Section("SELECTION") {
        CheckSetting("Copy on select", settings.copyOnSelect, { value -> onChange { it.copy(copyOnSelect = value) } }, Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        Caption("Puts what you select in the clipboard as soon as you release the mouse.")
        CheckSetting("Command marks", settings.commandMarks, { value -> onChange { it.copy(commandMarks = value) } }, Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        Caption("A mark beside each command: green when it succeeded, red when it failed. Click it to select its output.")
    }
    Separator()
    Section("TERMINAL CONTENTS") {
        CheckSetting("Restore terminal contents", settings.restoreContents, { value -> onChange { it.copy(restoreContents = value) } }, Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        Caption("What a terminal showed comes back when KaTTY starts again, not its programs. It is kept in files that only you can read; turning this off deletes them.")
    }
}

@Composable
private fun KeyboardPage(settings: Settings, onChange: ((Settings) -> Settings) -> Unit) {
    Section("OPTION AS ALT") {
        Choice(
            listOf(
                ChoiceOption(OptionKey.CHARACTERS, "None"),
                ChoiceOption(OptionKey.LEFT_IS_ALT, "Left"),
                ChoiceOption(OptionKey.RIGHT_IS_ALT, "Right"),
                ChoiceOption(OptionKey.BOTH_ARE_ALT, "Both"),
            ),
            settings.optionKey, { value -> onChange { it.copy(optionKey = value) } },
            Modifier.padding(horizontal = 4.dp),
        )
        Caption("Which Option keys are the program's Alt, which moves by word in a shell (Option+B, Option+F). The others type the characters of your layout.")
    }
    Separator()
    Section("SECURE KEYBOARD ENTRY") {
        Choice(
            listOf(
                ChoiceOption(SecureInput.NEVER, "Never"),
                ChoiceOption(SecureInput.PASSWORD_PROMPTS, "Passwords"),
                ChoiceOption(SecureInput.ALWAYS, "Always"),
            ),
            settings.secureInput, { value -> onChange { it.copy(secureInput = value) } },
            Modifier.padding(horizontal = 4.dp),
        )
        Caption("Keeps what is typed in a terminal from the other applications. Text expanders and global shortcuts stop working meanwhile.")
    }
}

@Composable
private fun AgentsPage(settings: Settings, onChange: ((Settings) -> Settings) -> Unit, agents: Set<Agent>) {
    Section("ATTENTION") {
        CheckSetting("Sound when an agent needs you", settings.attentionSound, { value -> onChange { it.copy(attentionSound = value) } }, Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        Caption("Plays the alert sound of macOS when an agent that you are not looking at waits for you or fails.")
    }
    Separator()
    Section("AGENT USAGE") {
        CheckSetting("Show agent usage", settings.showUsage, { value -> onChange { it.copy(showUsage = value) } }, Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        Choice(
            listOf(ChoiceOption(false, "Panel"), ChoiceOption(true, "Window")),
            settings.usageInWindow, { value -> onChange { it.copy(usageInWindow = value) } },
            Modifier.padding(horizontal = 4.dp),
            enabled = settings.showUsage,
        )
        Caption("The window is for another screen. Closing it puts the usage back in the panel.")
    }
    Separator()
    Section("AI COMMAND") {
        AiCommandField(settings.aiCommand, aiAgents(agents)) { command -> onChange { it.copy(aiCommand = command) } }
    }
}

/**
 * The AI command: a field that always shows the command in use (the default is in it while the setting is `null`), and is empty while the AI is
 * turned off; a select above it puts the command of one of the installed [agents] in it (the first one's is the default).
 */
@Composable
private fun AiCommandField(current: String?, agents: List<Agent>, onChange: (String?) -> Unit) {
    val colors = Katty.colors
    val default = agents.firstOrNull()?.defaultAiCommand
    val state = rememberTextFieldState(current ?: default.orEmpty())
    val latestDefault by rememberUpdatedState(default)
    val latestCurrent by rememberUpdatedState(current)
    val latestOnChange by rememberUpdatedState(onChange)
    // Every change is kept at once: nothing is lost when the window is closed while the field has the focus.
    LaunchedEffect(state) { snapshotFlow { state.text.toString() }.drop(1).collect { latestOnChange(aiCommandSetting(it, latestDefault)) } }
    // The agents are found after the window opens, and may be installed meanwhile: a field that follows the default shows the new one.
    LaunchedEffect(default) {
        if (latestCurrent == null && state.text.toString() != default.orEmpty()) state.setTextAndPlaceCursorAtEnd(default.orEmpty())
    }
    Caption(
        "What KaTTY runs to ask an agent something, such as the command that runs a project (Ask AI, in the question of ${AppShortcut.RUN.label}). " +
            "\$PROMPT is the question, and the first line that it prints is the answer. It runs with /bin/sh, in the directory that the question is about.",
    )
    AiAgentField(state.text.toString().trim(), agents) { state.setTextAndPlaceCursorAtEnd(it) }
    TextField(
        state,
        Modifier.padding(horizontal = 4.dp, vertical = 4.dp).fillMaxWidth().semantics { contentDescription = "AI command" },
        placeholder = { Text("Off", fontFamily = FontFamily.Monospace) },
        textStyle = JewelTheme.defaultTextStyle.copy(fontFamily = FontFamily.Monospace),
    )
    Text(
        if (agents.isEmpty()) "No installed agent has an AI command: type one. Empty, the AI is off." else "Empty, the AI is off: the run question has no Ask AI.",
        Modifier.padding(start = 8.dp, end = 8.dp, top = 2.dp),
        color = colors.muted, fontSize = 11.sp,
    )
}

/**
 * The agent whose command is in the AI command's field ([command]: "Custom" when it is none of theirs, "Off" when it is empty), and the menu that
 * puts one of the [agents]' commands in it, or empties it.
 */
@Composable
private fun AiAgentField(command: String, agents: List<Agent>, onChoose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = when {
        command.isEmpty() -> "Off"
        else -> agents.firstOrNull { it.defaultAiCommand == command }?.displayName ?: "Custom"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Agent", Modifier.width(48.dp * uiScaleFactor()).padding(start = 4.dp), color = Katty.colors.muted)
        Box(Modifier.weight(1f)) {
            Field(name, "AI agent: $name", { open = true })
            if (open) {
                KattyMenu(
                    menu {
                        agents.forEachIndexed { index, agent ->
                            action(agent.displayName + if (index == 0) " (default)" else "", hint = agent.defaultAiCommand) { onChoose(agent.defaultAiCommand.orEmpty()) }
                        }
                        if (agents.isNotEmpty()) divider()
                        action("Off") { onChoose("") }
                    },
                    onDismiss = { open = false },
                )
            }
        }
    }
}

/**
 * The environments: the folders they are read from, in their order; the one that new projects are given; and the ones that were found, with their
 * files and, under a file that has lines that are not read, how many. Never a value, nor the text of a line: the files hold secrets.
 */
@Composable
private fun EnvironmentsPage(settings: Settings, onChange: ((Settings) -> Settings) -> Unit, listing: EnvironmentListing, actions: EnvironmentActions) {
    val colors = Katty.colors
    val latestActions by rememberUpdatedState(actions)
    // The files are not watched: they are read again when the page is shown.
    LaunchedEffect(Unit) { latestActions.onRefresh() }
    val folders = settings.environmentDirectories
    Section("FOLDERS") {
        Caption("An environment is a file named after it, kodein.env for kodein, in one of these folders. When several have it, they are read in this order and the last one wins.")
        folders.forEachIndexed { index, folder ->
            fun moved(by: Int) = onChange { it.copy(environmentDirectories = it.environmentDirectories.toMutableList().apply { add(index + by, removeAt(index)) }) }
            Row(Modifier.padding(start = 8.dp, end = 4.dp).heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                OneLine(folder, Modifier.weight(1f), color = colors.text)
                KattyIconButton(KattyIcons.ChevronUp, "Move $folder up", { moved(-1) }, enabled = index > 0)
                KattyIconButton(KattyIcons.ChevronDown, "Move $folder down", { moved(1) }, enabled = index < folders.lastIndex)
                KattyIconButton(KattyIcons.Folder, "Open $folder", { actions.onOpenFolder(folder) })
                KattyIconButton(KattyIcons.Close, "Remove $folder", { onChange { it.copy(environmentDirectories = it.environmentDirectories.filterIndexed { i, _ -> i != index }) } })
            }
        }
        Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KattyTextButton("Add folder…", actions.onAddFolder)
            if (folders != listOf(Environments.DEFAULT_DIRECTORY)) {
                KattyTextButton("Reset", { onChange { it.copy(environmentDirectories = listOf(Environments.DEFAULT_DIRECTORY)) } }, description = "Reset the folders")
            }
        }
    }
    Separator()
    Section("DEFAULT ENVIRONMENT") {
        DefaultEnvironmentField(settings.defaultEnvironment, listing.names) { name -> onChange { it.copy(defaultEnvironment = name) } }
        Caption("A project that is added gets it, and keeps it when this changes. Its shells are started with it.")
        val count = actions.projectsWithoutEnvironment
        if (settings.defaultEnvironment != null && count > 0) {
            val projects = if (count == 1) "1 project has" else "$count projects have"
            Row(Modifier.padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                OneLine("$projects no environment.", Modifier.weight(1f), color = colors.muted)
                KattyTextButton("Apply", actions.onApplyDefault, description = "Apply the default environment to the projects that have none")
            }
        }
    }
    Separator()
    Section("FOUND") {
        if (listing.environments.isEmpty()) {
            Caption("None yet. A line such as CLAUDE_CONFIG_DIR=~/.claude-kodein in kodein.env gives the shells of its projects another Claude Code account.")
        }
        for (environment in listing.environments) {
            Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 2.dp).semantics(mergeDescendants = true) {}) {
                OneLine(environment.name, color = colors.text)
                for (file in environment.files) {
                    OneLine(homeAbbreviated(file.path.toString()), Modifier.padding(start = 12.dp), color = colors.muted, fontSize = 11.sp)
                    val problem = when {
                        file.unreadable -> "⚠ Cannot be read"
                        file.ignoredLines == 1 -> "⚠ 1 line ignored (line ${file.firstIgnoredLine})"
                        file.ignoredLines > 1 -> "⚠ ${file.ignoredLines} lines ignored (line ${file.firstIgnoredLine})"
                        else -> null
                    }
                    if (problem != null) OneLine(problem, Modifier.padding(start = 12.dp), color = colors.err, fontSize = 11.sp)
                }
            }
        }
    }
}

/** The default environment: "None", or one of the [names] that exist; one that has no file any more says so. */
@Composable
private fun DefaultEnvironmentField(current: String?, names: Set<String>, onChoose: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = when {
        current == null -> "None"
        current !in names -> "$current (not found)"
        else -> current
    }
    Box {
        Field(label, "Default environment: $label", { open = true })
        if (open) {
            KattyMenu(
                menu {
                    action("None", checked = current == null) { onChoose(null) }
                    if (names.isNotEmpty()) divider()
                    for (name in names) action(name, checked = name == current) { onChoose(name) }
                },
                onDismiss = { open = false },
            )
        }
    }
}

/** [path] with the home folder written `~`. */
internal fun homeAbbreviated(path: String, home: String = System.getProperty("user.home")): String = when {
    path == home -> "~"
    path.startsWith("$home/") -> "~" + path.removePrefix(home)
    else -> path
}

@Composable
private fun Section(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionLabel(label, Modifier.padding(start = 10.dp, top = 8.dp, bottom = 4.dp))
        content()
    }
}

@Composable
private fun Separator() {
    Divider(org.jetbrains.jewel.ui.Orientation.Horizontal, Modifier.padding(vertical = 6.dp, horizontal = 4.dp))
}

@Composable
private fun Caption(text: String) {
    Text(text, Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 2.dp), color = Katty.colors.muted, fontSize = 11.sp)
}

/** A field that opens a choice: its value, and a chevron. */
@Composable
internal fun Field(value: String, description: String, onClick: () -> Unit, chevron: Boolean = true, modifier: Modifier = Modifier, leading: @Composable () -> Unit = {}) {
    val colors = Katty.colors
    Row(
        modifier.padding(horizontal = 4.dp).fillMaxWidth().heightIn(min = 30.dp).clip(RoundedCornerShape(5.dp)).border(1.dp, colors.line, RoundedCornerShape(5.dp))
            .semantics { contentDescription = description }
            .clickable(role = Role.DropdownList, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leading()
        OneLine(value, Modifier.weight(1f), color = colors.text)
        if (chevron) Icon(KattyIcons.ChevronDown, colors.muted, size = 14.dp)
    }
}

/** The color scheme of the terminals in one theme of the application ([mode]: Light or Dark), with its swatch: a click opens the picker on it. */
@Composable
private fun SchemeField(mode: String, name: String, scheme: ITermColorSchemeInfo?, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(mode, Modifier.width(40.dp * uiScaleFactor()).padding(start = 4.dp), color = Katty.colors.muted)
        Field(name, "Terminal color scheme, ${mode.lowercase()} theme: $name", onClick, modifier = Modifier.weight(1f)) {
            if (scheme != null) Swatches(scheme)
        }
    }
}

/** Four colors of a scheme side by side: its background and three of its colors. */
@Composable
private fun Swatches(scheme: ITermColorSchemeInfo) {
    val swatch = listOf(scheme.background, scheme.accents[0], scheme.accents[1], scheme.accents[3])
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (color in swatch) Box(Modifier.size(width = 8.dp, height = 14.dp).clip(RoundedCornerShape(2.dp)).background(color.toColor()))
    }
}

@Composable
private fun FontField(family: String?, families: List<String>, onChoose: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = family ?: BUNDLED_FONT_NAME
    Box {
        Field(name, "Font family: $name", { open = true })
        if (open) {
            KattyMenu(
                menu {
                    action("$BUNDLED_FONT_NAME (bundled)") { onChoose(null) }
                    divider()
                    for (candidate in families.filter { it != BUNDLED_FONT_NAME }) action(candidate) { onChoose(candidate) }
                },
                onDismiss = { open = false },
            )
        }
    }
}

/** The size of the font: a stepper by half a pixel, which keeps to the range that can be used. */
@Composable
internal fun FontSizeStepper(size: Float, onChange: (Float) -> Unit) {
    Stepper("Font size", formatSize(size), "font size", { onChange(size - FONT_STEP) }, { onChange(size + FONT_STEP) })
}

/** The size of the text of the application: a stepper through the sizes it can have, as a percentage of the usual one. */
@Composable
internal fun UiScaleStepper(settings: Settings, onChange: (Float) -> Unit) {
    Stepper(
        "Text size", formatScale(settings.effectiveUiScale), "interface text size",
        { onChange(settings.steppedUiScale(-1)) }, { onChange(settings.steppedUiScale(1)) },
        canDecrease = settings.steppedUiScale(-1) != settings.effectiveUiScale,
        canIncrease = settings.steppedUiScale(1) != settings.effectiveUiScale,
    )
}

/**
 * A value with a button on each side that makes it smaller and bigger. [what] is what the buttons say to a screen reader: "Decrease $what".
 * A button that cannot go further is dimmed and does nothing.
 */
@Composable
private fun Stepper(
    label: String, value: String, what: String, onDecrease: () -> Unit, onIncrease: () -> Unit, canDecrease: Boolean = true, canIncrease: Boolean = true,
) {
    val colors = Katty.colors
    Row(Modifier.padding(horizontal = 8.dp).fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).semantics { contentDescription = label }, color = colors.text)
        Row(Modifier.clip(RoundedCornerShape(5.dp)).border(1.dp, colors.line, RoundedCornerShape(5.dp)), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(26.dp).clickable(enabled = canDecrease, role = Role.Button, onClick = onDecrease).semantics { contentDescription = "Decrease $what" },
                contentAlignment = Alignment.Center,
            ) { Text("−", fontSize = 15.sp, color = if (canDecrease) colors.text else colors.muted) }
            Box(Modifier.width(1.dp).height(26.dp).background(colors.line))
            Text(value, Modifier.width(56.dp * uiScaleFactor()), color = colors.text, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Box(Modifier.width(1.dp).height(26.dp).background(colors.line))
            Box(
                Modifier.size(26.dp).clickable(enabled = canIncrease, role = Role.Button, onClick = onIncrease).semantics { contentDescription = "Increase $what" },
                contentAlignment = Alignment.Center,
            ) { Text("+", fontSize = 15.sp, color = if (canIncrease) colors.text else colors.muted) }
        }
    }
}

private const val FONT_STEP = 0.5f

/** The size as the stepper shows it: `12.5 px`, and `13 px` for a whole number. */
internal fun formatSize(size: Float): String = (if (size % 1f == 0f) size.toInt().toString() else size.toString()) + " px"

/** The scale as the stepper shows it: `110%`. */
internal fun formatScale(scale: Float): String = "${kotlin.math.round(scale * 100).toInt()}%"
