package app.katty.core

/** How the application is arranged once the layout mode is settled by the window's shape. */
public enum class Orientation { HORIZONTAL, VERTICAL }

/** The arrangement that [this] gives a window of this size: auto is vertical for a window that is taller than it is wide. */
public fun LayoutMode.orientation(width: Float, height: Float): Orientation = when (this) {
    LayoutMode.HORIZONTAL -> Orientation.HORIZONTAL
    LayoutMode.VERTICAL -> Orientation.VERTICAL
    LayoutMode.AUTO -> if (height > width) Orientation.VERTICAL else Orientation.HORIZONTAL
}

/**
 * The two letters that stand for a project when there is little room, as in the collapsed sidebar: the first letters of its first two
 * words (`api-gateway` is `AG`), or the first two letters of a name that is one word.
 */
public fun initialsOf(name: String): String {
    val words = name.split(Regex("[\\s\\-_./]+")).filter { it.isNotEmpty() }
    val letters = when {
        words.size >= 2 -> "${words[0].first()}${words[1].first()}"
        words.size == 1 -> words[0].take(2)
        else -> "?"
    }
    return letters.uppercase()
}
