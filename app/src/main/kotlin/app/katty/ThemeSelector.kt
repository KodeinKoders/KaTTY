package app.katty

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val FIELD_WIDTH = 300.dp
private val MENU_ITEM_HEIGHT = 48.dp
private val MENU_MAX_HEIGHT = 320.dp

/**
 * A field that shows the name of the theme, and lists the [names] that have what is typed in it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThemeSelector(
    names: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    // What is typed. It is the selected name until something is typed, and then the menu is filtered by it.
    var query by remember(selected) { mutableStateOf(selected) }
    val matches = remember(names, query, selected) {
        if (query == selected) names else names.filter { it.contains(query.trim(), ignoreCase = true) }
    }

    ExposedDropdownMenuBox(expanded, { expanded = it }, modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                expanded = true
            },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable).width(FIELD_WIDTH),
            label = { Text("Theme") },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                // What was typed without choosing is dropped.
                query = selected
            },
        ) {
            if (matches.isEmpty()) {
                DropdownMenuItem(text = { Text("No such theme") }, onClick = {}, enabled = false)
            }
            // The menu has 800 entries: they are only composed when they are seen. The menu asks its content
            // for its intrinsic size, which a lazy list cannot answer unless it has a fixed one: the width is
            // the one of the field, which the menu has anyway, and the height is what the entries take, up to
            // a limit.
            LazyColumn(Modifier.size(FIELD_WIDTH, minOf(MENU_ITEM_HEIGHT * matches.size, MENU_MAX_HEIGHT))) {
                items(matches, key = { it }) { name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            expanded = false
                            query = name
                            onSelect(name)
                        },
                    )
                }
            }
        }
    }
}
