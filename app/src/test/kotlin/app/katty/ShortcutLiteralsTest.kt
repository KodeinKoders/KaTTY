package app.katty

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A shortcut is written in one place, [AppShortcut] and its bindings, which the user can change: a label written as a string anywhere else
 * shows the default keys whatever the user chose.
 */
class ShortcutLiteralsTest {
    // The files where shortcuts are made, and the keys that are fixed on purpose: the modifiers' symbols, and the shortcuts dialog's own key.
    private val owners = setOf("AppShortcuts.kt", "ShortcutBindings.kt")
    private val fixed = listOf("\"⌘⌥⇧⌃\"", "Hint(listOf(\"⌘\", \"⌫\"), \"Back to the default\")")

    @Test
    fun noShortcutIsWrittenAsAStringOutsideItsPlace() {
        val sources = File("src/main/kotlin")
        val found = sources.walk().filter { it.isFile && it.extension == "kt" && it.name !in owners }.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> "\"⌘" in line && !line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/**") } }
                .filter { (_, line) -> fixed.none { it in line } }
                .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
        }.toList()

        assertEquals(emptyList(), found)
    }
}
