package app.katty.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.katty.terminal.TerminalColor
import app.katty.terminal.TerminalSession
import app.katty.terminal.compose.Terminal
import app.katty.terminal.themes.ITermColorSchemeInfo
import app.katty.terminal.themes.ITermColorSchemes
import app.katty.ui.ButtonSize
import app.katty.ui.Choice
import app.katty.ui.ChoiceOption
import app.katty.ui.DialogScrim
import app.katty.ui.Icon
import app.katty.ui.Katty
import app.katty.ui.KattyIconButton
import app.katty.ui.KattyIcons
import app.katty.ui.OneLine
import app.katty.ui.SectionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/** Which schemes a list of them shows. */
enum class SchemeFilter { ALL, LIGHT, DARK }

/**
 * The schemes that match what the user typed and the kind that they chose. The name is searched without regard for the case, and anywhere
 * in it.
 */
fun filterSchemes(entries: List<ITermColorSchemeInfo>, query: String, filter: SchemeFilter): List<ITermColorSchemeInfo> {
    val text = query.trim()
    return entries.filter { entry ->
        when (filter) {
            SchemeFilter.ALL -> true
            SchemeFilter.LIGHT -> !entry.dark
            SchemeFilter.DARK -> entry.dark
        } && entry.name.contains(text, ignoreCase = true)
    }
}

internal fun TerminalColor.toColor(): Color = Color(0xFF000000.toInt() or rgb)

/**
 * The picker of a terminal's color scheme: a search field, the schemes (all, light or dark) with a swatch for each, and a preview of the
 * selected one in a real terminal with its palette. Choosing a scheme applies it at once, and the picker stays open until it is closed.
 *
 * @param selected The scheme that is chosen; `null` for the default, when there is one to choose ([defaultName] says which it is).
 * @param defaultName The name of the default scheme, when what is being chosen is the scheme of a terminal that may follow it.
 */
@Composable
fun ColorSchemePicker(
    entries: List<ITermColorSchemeInfo>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    defaultName: String? = null,
) {
    val colors = Katty.colors
    val query = rememberTextFieldState()
    var filter by remember { mutableStateOf(SchemeFilter.ALL) }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(query) { snapshotFlow { query.text.toString() }.collect { text = it } }
    val shown = remember(entries, text, filter) { filterSchemes(entries, text, filter) }
    // What the preview shows: the chosen scheme, or the default for a terminal that follows it.
    val previewed = selected ?: defaultName ?: entries.firstOrNull()?.name

    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    DialogScrim(
        modifier.focusRequester(focus).onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                onClose()
                true
            } else {
                false
            }
        },
        width = 920.dp,
    ) {
        Column(Modifier.height(640.dp).semantics { contentDescription = "Terminal color scheme" }) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Terminal color scheme", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
                KattyIconButton(KattyIcons.Close, "Close", onClose)
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.width(340.dp).fillMaxHeight().padding(start = 12.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(query, Modifier.fillMaxWidth().semantics { contentDescription = "Search color schemes" }, placeholder = { Text("Search schemes") })
                    Choice(
                        listOf(
                            ChoiceOption(SchemeFilter.ALL, "All ${entries.size}"),
                            ChoiceOption(SchemeFilter.LIGHT, "Light ${entries.count { !it.dark }}"),
                            ChoiceOption(SchemeFilter.DARK, "Dark ${entries.count { it.dark }}"),
                        ),
                        filter, { filter = it },
                    )
                    // The chosen scheme is in view when the picker opens: the list starts at it, rather than scrolls to it once it is laid out.
                    val hasDefault = defaultName != null
                    val listState = rememberLazyListState(
                        initialFirstVisibleItemIndex = remember { shown.indexOfFirst { it.name == selected }.let { if (it < 0) 0 else it + if (hasDefault) 1 else 0 } },
                    )
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
                        if (defaultName != null && text.isBlank() && filter == SchemeFilter.ALL) {
                            item(key = "default") { DefaultRow(defaultName, selected == null) { onSelect(null) } }
                        }
                        items(shown, key = { it.name }) { entry -> SchemeRow(entry, entry.name == selected) { onSelect(entry.name) } }
                        if (shown.isEmpty()) {
                            item { Text("No schemes match your search.", Modifier.padding(12.dp), color = colors.muted) }
                        }
                    }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(colors.line))
                if (previewed != null) {
                    SchemePreview(entries.firstOrNull { it.name == previewed }, Modifier.weight(1f).fillMaxHeight().padding(16.dp))
                }
            }
        }
    }
}

@Composable
private fun SchemeRow(entry: ITermColorSchemeInfo, selected: Boolean, onClick: () -> Unit) {
    val colors = Katty.colors
    Row(
        Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(6.dp))
            .background(if (selected) colors.sel else Color.Transparent)
            .semantics { contentDescription = entry.name; this.selected = selected }
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(width = 32.dp, height = 26.dp).clip(RoundedCornerShape(4.dp)).background(entry.background.toColor()).border(1.dp, colors.line, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
            Text("Aa", color = entry.foreground.toColor(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        OneLine(entry.name, Modifier.weight(1f), color = colors.text)
        Text(if (entry.dark) "Dark" else "Light", color = colors.muted, fontSize = 12.sp)
        if (selected) Icon(KattyIcons.Check, colors.accent, size = 14.dp) else Spacer(Modifier.size(14.dp))
    }
}

@Composable
private fun DefaultRow(defaultName: String, selected: Boolean, onClick: () -> Unit) {
    val colors = Katty.colors
    Row(
        Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(6.dp))
            .background(if (selected) colors.sel else Color.Transparent)
            .semantics { contentDescription = "Default"; this.selected = selected }
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(KattyIcons.Palette, colors.muted, size = 16.dp, modifier = Modifier.padding(horizontal = 8.dp))
        Text("Default", Modifier.weight(1f), color = colors.text)
        Text(defaultName, color = colors.muted, fontSize = 12.sp, maxLines = 1)
        if (selected) Icon(KattyIcons.Check, colors.accent, size = 14.dp) else Spacer(Modifier.size(14.dp))
    }
}

// What the preview writes: the output of a build and its tests, with the colors that a scheme is made of.
private const val PREVIEW_TEXT =
    "\u001b[32m~/dev/api-gateway\u001b[0m \u001b[34mrefactor-auth\u001b[0m\r\n" +
        "\u001b[1m❯\u001b[0m git diff --stat\r\n" +
        " src/auth/middleware.ts | 12 \u001b[32m+++++\u001b[31m---\u001b[0m\r\n" +
        " src/auth/validate.ts   | 28 \u001b[32m++++++++\u001b[0m\r\n" +
        "\u001b[1m❯\u001b[0m npm test\r\n" +
        "\u001b[32m✓\u001b[0m validateSession accepts a valid token\r\n" +
        "\u001b[31m✗\u001b[0m validateSession rejects an expired token\r\n" +
        "\u001b[33m!\u001b[0m 1 test skipped\r\n" +
        "\u001b[36minfo\u001b[0m Ran 24 tests in 1.8s\r\n" +
        "\u001b[35m❯\u001b[0m "

/** The selected scheme in a terminal that runs nothing and is written to, and the swatches of its palette. */
@Composable
private fun SchemePreview(entry: ITermColorSchemeInfo?, modifier: Modifier) {
    val colors = Katty.colors
    val session = remember {
        // A program that does nothing, for the time that the picker is open: the preview only has to be a terminal that can be drawn.
        // It starts with the colors of the scheme, so that it is not drawn in the wrong ones for a moment.
        val theme = entry?.name?.let { name -> runCatching { ITermColorSchemes.load(name) }.getOrNull() }
        TerminalSession.start(cols = 60, rows = 12, command = listOf("/bin/sleep", "86400"), shellIntegration = false, theme = theme)
            .also { it.writeToScreen(PREVIEW_TEXT) }
    }
    DisposableEffect(session) { onDispose { session.close() } }
    LaunchedEffect(entry?.name) {
        val name = entry?.name ?: return@LaunchedEffect
        val theme = withContext(Dispatchers.IO) { runCatching { ITermColorSchemes.load(name) }.getOrNull() } ?: return@LaunchedEffect
        session.setTheme(theme)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OneLine(entry?.name ?: "", Modifier.weight(1f, fill = false), color = colors.text, fontSize = 16.sp)
            Text(if (entry?.dark == true) "Dark" else "Light", color = colors.muted)
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(6.dp)).border(1.dp, colors.line, RoundedCornerShape(6.dp))) {
            Terminal(session, Modifier.fillMaxSize(), fontSize = 12.sp, focusOnStart = false)
        }
        SectionLabel("PALETTE")
        if (entry != null) {
            val swatches = listOf("Bg" to entry.background, "Fg" to entry.foreground) +
                listOf("Red", "Green", "Yellow", "Blue", "Magenta", "Cyan").zip(entry.accents)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, color) in swatches) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(color.toColor()).border(1.dp, colors.line, RoundedCornerShape(6.dp))
                                .semantics { contentDescription = "$label #%06X".format(color.rgb) },
                        )
                        Text(label, color = colors.muted, fontSize = 11.sp, fontFamily = FontFamily.Default)
                    }
                }
            }
        }
    }
}
