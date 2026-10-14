package app.katty.terminal.themes

import app.katty.terminal.TerminalColor
import app.katty.terminal.TerminalTheme

/**
 * The color schemes of the iTerm2-Color-Schemes collection, which are in this module's resources.
 */
public object ITermColorSchemes {
    private const val DIRECTORY = "/app/katty/terminal/themes/schemes"

    /**
     * The names of the schemes, sorted, which is what [load] takes. Reading it costs a file, not the parsing
     * of the schemes.
     */
    public val names: List<String> by lazy {
        val index = ITermColorSchemes::class.java.getResourceAsStream("$DIRECTORY/index.txt")
            ?: throw IllegalStateException("The index of the color schemes is missing from the resources")
        index.use { it.reader(Charsets.UTF_8).readLines() }.filter { it.isNotEmpty() }
    }

    private val known: Set<String> by lazy { names.toSet() }

    /**
     * What a picker shows of each scheme, in the order of [names]: read from an index that is made when the module is built, so that
     * the schemes are not parsed to draw a list of them. [load] has the whole scheme.
     */
    public val entries: List<ITermColorSchemeInfo> by lazy {
        val index = ITermColorSchemes::class.java.getResourceAsStream("$DIRECTORY/index.tsv")
            ?: throw IllegalStateException("The index of the color schemes' colors is missing from the resources")
        index.use { it.reader(Charsets.UTF_8).readLines() }.filter { it.isNotEmpty() }.map { line ->
            val fields = line.split('\t')
            fun color(i: Int) = TerminalColor(fields[i].toInt(16))
            ITermColorSchemeInfo(
                name = fields[0],
                dark = fields[1] == "1",
                background = color(2),
                foreground = color(3),
                accents = (4..9).map(::color),
            )
        }
    }

    /**
     * Reads the scheme called [name].
     *
     * @throws IllegalArgumentException If there is no such scheme, or it cannot be read.
     */
    public fun load(name: String): TerminalTheme {
        // Not a path: only what is in the index is read.
        require(name in known) { "There is no color scheme called '$name'" }
        val stream = ITermColorSchemes::class.java.getResourceAsStream("$DIRECTORY/$name.itermcolors")
            ?: throw IllegalStateException("The color scheme '$name' is in the index but not in the resources")
        val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        try {
            return parseITermColorScheme(text)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("The color scheme '$name' cannot be read: ${e.message}", e)
        }
    }
}
