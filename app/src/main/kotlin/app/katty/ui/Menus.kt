package app.katty.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.input.InputMode
import org.jetbrains.jewel.ui.component.MenuScope
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.separator

/** An entry of a menu of the application. */
sealed interface MenuEntry

/**
 * An item that does something.
 *
 * @param shortcut The keys of its shortcut, as the design writes them: `⌘N`.
 * @param danger Whether it destroys something: it is red.
 */
data class MenuAction(
    val label: String,
    val shortcut: String? = null,
    val danger: Boolean = false,
    val enabled: Boolean = true,
    /** A value after the label, muted: what the item is set to now. */
    val hint: String? = null,
    val onClick: () -> Unit,
) : MenuEntry

/** A line between groups of items. */
data object MenuDivider : MenuEntry

/** An item that opens a menu of its own, with [hint] (the current value, for instance) after its label. */
data class MenuSubmenu(val label: String, val entries: List<MenuEntry>, val hint: String? = null) : MenuEntry

/** Builds a list of entries: `menu { action("Copy", "⌘C") { … }; divider(); … }`. */
class MenuBuilder {
    private val entries = ArrayList<MenuEntry>()

    fun action(label: String, shortcut: String? = null, danger: Boolean = false, enabled: Boolean = true, hint: String? = null, onClick: () -> Unit) {
        entries += MenuAction(label, shortcut, danger, enabled, hint, onClick)
    }

    fun divider() {
        // Two lines in a row, or one at the ends, say nothing.
        if (entries.isNotEmpty() && entries.last() != MenuDivider) entries += MenuDivider
    }

    fun submenu(label: String, hint: String? = null, build: MenuBuilder.() -> Unit) {
        entries += MenuSubmenu(label, menu(build), hint)
    }

    fun build(): List<MenuEntry> = entries.toList().dropLastWhile { it == MenuDivider }
}

fun menu(build: MenuBuilder.() -> Unit): List<MenuEntry> = MenuBuilder().apply(build).build()

/**
 * A popup menu of the entries, Jewel's: at [offset] from the top left of the place where it is composed, or under it when there is no
 * offset. It closes when something is chosen, or when the user clicks outside. A [MenuSubmenu] is Jewel's own submenu, a popup that opens
 * beside the item when the pointer is on it.
 */
@Composable
fun KattyMenu(entries: List<MenuEntry>, onDismiss: () -> Unit, offset: IntOffset? = null) {
    // Jewel keeps, for each menu, a controller that knows whether the pointer is over it, and makes a new one whenever the callback that it was
    // given is a new object. A callback made at each drawing of the parent (and a pane's header is drawn often) made the menu forget, while a
    // submenu was open, that a click was on it, so that the click closed everything instead of choosing the item. The callback is made once,
    // and calls the latest one.
    val latest by rememberUpdatedState(onDismiss)
    val dismiss = remember { { _: InputMode -> latest(); true } }
    val close = remember { { latest() } }
    val content: MenuScope.() -> Unit = { entries(entries, close) }
    if (offset != null) {
        val position = remember(offset) { AtOffset(offset) }
        PopupMenu(onDismissRequest = dismiss, popupPositionProvider = position, content = content)
    } else {
        PopupMenu(onDismissRequest = dismiss, horizontalAlignment = Alignment.Start, content = content)
    }
}

private fun MenuScope.entries(entries: List<MenuEntry>, close: () -> Unit) {
    for (entry in entries) {
        when (entry) {
            is MenuAction -> selectableItem(
                selected = false,
                keybinding = entry.shortcut?.let { setOf(it) },
                onClick = { close(); entry.onClick() },
                enabled = entry.enabled,
            ) {
                if (entry.hint == null) {
                    Text(entry.label, color = if (entry.danger) Katty.colors.err else androidx.compose.ui.graphics.Color.Unspecified)
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.label)
                        Spacer(Modifier.width(12.dp))
                        Text(entry.hint, color = Katty.colors.muted, fontSize = 12.sp)
                    }
                }
            }
            MenuDivider -> separator()
            is MenuSubmenu -> submenu(submenu = { entries(entry.entries, close) }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.label)
                    if (entry.hint != null) {
                        Spacer(Modifier.width(12.dp))
                        Text(entry.hint, color = Katty.colors.muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// Puts a popup at a point of the window that it is composed in, and keeps it inside the window.
private class AtOffset(private val offset: IntOffset) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.left + offset.x).coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val y = (anchorBounds.top + offset.y).coerceAtMost(windowSize.height - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

