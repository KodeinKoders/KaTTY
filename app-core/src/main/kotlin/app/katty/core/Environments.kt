package app.katty.core

import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/*
 * An environment is a set of variables given to the shells of a workspace, on top of the ones KaTTY was started with: `sunday` is every
 * `sunday.env` of the folders of the settings, read in their order. A file is in the dotenv format:
 *
 * ```
 * # A comment.
 * CLAUDE_CONFIG_DIR=~/.claude-sunday
 * export GH_TOKEN="ghp_…"
 * PATH="${HOME}/bin:${PATH}"
 * LITERAL='$not expanded'
 * unset ANTHROPIC_API_KEY
 * ```
 *
 * Nothing here ever keeps the text of a line, or a value, in what it reports: a file holds secrets, and what it reports is drawn and logged.
 */

/** What can be wrong with a line of an environment file, or with the file. */
public enum class EnvironmentProblemKind {
    /** Neither `NAME=value` nor `unset NAME`, or with something after its closing quote. The line is ignored. */
    INVALID_LINE,

    /** A name that is not one of a variable (`[A-Za-z_][A-Za-z0-9_]*`). The line is ignored. */
    INVALID_NAME,

    /** A quote that is never closed. The line where it opens is ignored, and the lines after it are read as lines of their own. */
    UNTERMINATED_QUOTE,

    /** `$(…)`, `${NAME:-…}` or another expansion of the shell that is not read. The line is ignored. */
    UNSUPPORTED_EXPANSION,

    /** A variable that KaTTY sets itself (see [Environments.isReserved]). The line is ignored. */
    RESERVED_VARIABLE,

    /** `${NAME}` of a variable that is not defined, which is then empty. The line is not ignored. */
    UNDEFINED_REFERENCE,

    /** The file could not be read. It is left out. */
    UNREADABLE_FILE,
}

/**
 * Something wrong in [file]: its [line] (from 1), `null` for the whole file, and the [variable] it is about when it is a valid name (never the
 * text of the line, which may hold a secret).
 */
public data class EnvironmentProblem(
    val file: Path,
    val line: Int?,
    val kind: EnvironmentProblemKind,
    val variable: String? = null,
) {
    /** Whether the line was left out because of it. */
    val ignoresLine: Boolean get() = line != null && kind != EnvironmentProblemKind.UNDEFINED_REFERENCE
}

/** A file of an environment, with what is wrong in it on its own (a reference to another file's variable is not looked at). */
public data class EnvironmentFile(val path: Path, val problems: List<EnvironmentProblem> = emptyList()) {
    /** Whether it could not be read. */
    val unreadable: Boolean get() = problems.any { it.kind == EnvironmentProblemKind.UNREADABLE_FILE }

    /** How many of its lines were left out. */
    val ignoredLines: Int get() = problems.filter { it.ignoresLine }.mapNotNullTo(HashSet()) { it.line }.size

    /** The first of the lines that were left out, `null` when none was. */
    val firstIgnoredLine: Int? get() = problems.filter { it.ignoresLine }.mapNotNull { it.line }.minOrNull()
}

/** An environment that was found: its [files], in the order of the folders, which is the order they are read in. */
public data class EnvironmentInfo(val name: String, val files: List<EnvironmentFile>)

/** The environments found in [directories] (expanded, in their order), sorted by name. */
public data class EnvironmentListing(val environments: List<EnvironmentInfo>, val directories: List<Path>) {
    /** The names of the environments that exist. */
    val names: Set<String> get() = environments.mapTo(LinkedHashSet()) { it.name }

    public companion object {
        public val EMPTY: EnvironmentListing = EnvironmentListing(emptyList(), emptyList())
    }
}

/**
 * What an environment changes in the environment of a process: [variables] set (over the ones it has), and [unset] taken out of it.
 *
 * Not a data class, and [toString] only gives the names: the values are secrets.
 */
public class EnvironmentChange(public val variables: Map<String, String>, public val unset: Set<String>) {
    /** Whether it changes nothing. */
    public val isEmpty: Boolean get() = variables.isEmpty() && unset.isEmpty()

    /** [base] with this change made to it. */
    public fun applyTo(base: MutableMap<String, String>) {
        base.keys.removeAll(unset)
        base.putAll(variables)
    }

    override fun equals(other: Any?): Boolean = other is EnvironmentChange && variables == other.variables && unset == other.unset

    override fun hashCode(): Int = 31 * variables.hashCode() + unset.hashCode()

    override fun toString(): String = "EnvironmentChange(variables=${variables.keys}, unset=$unset)"

    public companion object {
        public val NONE: EnvironmentChange = EnvironmentChange(emptyMap(), emptySet())
    }
}

/** What the environment of a workspace is when its shells start. */
public sealed interface EnvironmentResolution {
    /** What it changes in the environment that KaTTY was started with. */
    public val change: EnvironmentChange

    /** No environment: the one KaTTY was started with. */
    public data object Standard : EnvironmentResolution {
        override val change: EnvironmentChange get() = EnvironmentChange.NONE
    }

    /** An environment that has no file in any of the folders: its shells start as [Standard] ones. */
    public data class Missing(val name: String) : EnvironmentResolution {
        override val change: EnvironmentChange get() = EnvironmentChange.NONE
    }

    /** An environment read from its [files], with what was wrong in them. */
    public data class Resolved(
        val name: String,
        override val change: EnvironmentChange,
        val files: List<Path>,
        val problems: List<EnvironmentProblem>,
    ) : EnvironmentResolution
}

/** Reading the environments: finding their files, and what they change. */
public object Environments {
    /** The folder that the settings list first, until the user changes them. */
    public const val DEFAULT_DIRECTORY: String = "~/.config/KaTTY/environments"

    private const val EXTENSION = ".env"

    private val userHome: String get() = System.getProperty("user.home")

    /**
     * Whether [variable] is one that KaTTY sets in its terminals, which an environment cannot set or take out: the terminal's type and name, and
     * what the shell integration uses (`KATTY_…`). `ZDOTDIR` and `XDG_DATA_DIRS` are not, since the shell integration keeps the user's.
     */
    public fun isReserved(variable: String): Boolean = variable in RESERVED || variable.startsWith("KATTY_")

    private val RESERVED = setOf("TERM", "COLORTERM", "TERM_PROGRAM", "TERM_PROGRAM_VERSION")

    /** Whether [name] can be the name of an environment: a file name with no folder, not hidden, and with no control character. */
    public fun isValidName(name: String): Boolean =
        name.isNotEmpty() && !name.startsWith('.') && '/' !in name && name.none { it.isISOControl() }

    /** [path] with a `~` or `~/` that starts it made [home]. */
    public fun expandHome(path: String, home: String = userHome): String = when {
        path == "~" -> home
        path.startsWith("~/") -> home + path.substring(1)
        else -> path
    }

    /** The environments of [directories] (with `~` for [home]), sorted by name; a folder that does not exist has none. */
    public fun list(directories: List<String>, home: String = userHome): EnvironmentListing {
        val folders = directories.map { Paths.get(expandHome(it, home)) }
        val byName = LinkedHashMap<String, MutableList<EnvironmentFile>>()
        for (folder in folders) {
            for (file in environmentFiles(folder)) {
                val name = file.name.removeSuffix(EXTENSION)
                byName.getOrPut(name) { ArrayList() } += EnvironmentFile(file, problemsOf(file, home))
            }
        }
        val environments = byName.entries.sortedBy { it.key }.map { (name, files) -> EnvironmentInfo(name, files) }
        return EnvironmentListing(environments, folders)
    }

    /** The files of the environment [name] in [directories], in their order. */
    public fun files(name: String, directories: List<String>, home: String = userHome): List<Path> {
        if (!isValidName(name)) return emptyList()
        return directories.map { Paths.get(expandHome(it, home)).resolve(name + EXTENSION) }.filter { it.isRegularFile() }
    }

    /**
     * What the environment [name] is: [EnvironmentResolution.Standard] for `null`, [EnvironmentResolution.Missing] when no folder of [directories]
     * has its file. A `${NAME}` is what a line above set, in this file or in the file of a folder before it, else what [base] has.
     */
    public fun resolve(
        name: String?,
        directories: List<String>,
        base: Map<String, String> = System.getenv(),
        home: String = userHome,
    ): EnvironmentResolution {
        if (name == null) return EnvironmentResolution.Standard
        val files = files(name, directories, home)
        if (files.isEmpty()) return EnvironmentResolution.Missing(name)
        val problems = ArrayList<EnvironmentProblem>()
        val variables = LinkedHashMap<String, String>()
        val unset = LinkedHashSet<String>()
        for (file in files) {
            val text = read(file)
            if (text == null) {
                problems += EnvironmentProblem(file, null, EnvironmentProblemKind.UNREADABLE_FILE)
                continue
            }
            val parsed = parse(file, text, home)
            problems += parsed.problems
            for (entry in parsed.entries) {
                when (entry) {
                    is Entry.Assign -> {
                        val value = buildString {
                            for (part in entry.parts) {
                                when (part) {
                                    is Part.Text -> append(part.text)
                                    is Part.Reference -> {
                                        val resolved = variables[part.name] ?: base[part.name]?.takeUnless { part.name in unset }
                                        if (resolved == null) problems += EnvironmentProblem(file, entry.line, EnvironmentProblemKind.UNDEFINED_REFERENCE, part.name)
                                        append(resolved.orEmpty())
                                    }
                                }
                            }
                        }
                        variables[entry.name] = value
                        unset -= entry.name
                    }

                    is Entry.Unset -> for (variable in entry.names) {
                        variables -= variable
                        unset += variable
                    }
                }
            }
        }
        return EnvironmentResolution.Resolved(name, EnvironmentChange(variables, unset), files, problems)
    }

    private fun environmentFiles(folder: Path): List<Path> {
        if (!folder.isDirectory()) return emptyList()
        val entries = try {
            folder.listDirectoryEntries("*$EXTENSION")
        } catch (_: IOException) {
            return emptyList()
        }
        return entries.filter { isValidName(it.name.removeSuffix(EXTENSION)) && it.isRegularFile() }.sortedBy { it.name }
    }

    private fun problemsOf(file: Path, home: String): List<EnvironmentProblem> {
        val text = read(file) ?: return listOf(EnvironmentProblem(file, null, EnvironmentProblemKind.UNREADABLE_FILE))
        return parse(file, text, home).problems
    }

    private fun read(file: Path): String? = try {
        file.readText()
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    internal sealed interface Part {
        data class Text(val text: String) : Part

        data class Reference(val name: String) : Part
    }

    internal sealed interface Entry {
        val line: Int

        class Assign(override val line: Int, val name: String, val parts: List<Part>) : Entry

        class Unset(override val line: Int, val names: List<String>) : Entry
    }

    internal class Parsed(val entries: List<Entry>, val problems: List<EnvironmentProblem>)

    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

    private class Failure(val kind: EnvironmentProblemKind) : Exception(null, null, false, false)

    /** The lines of [text], the file [file]. */
    internal fun parse(file: Path, text: String, home: String = userHome): Parsed {
        val lines = text.split('\n').map { it.removeSuffix("\r") }
        val entries = ArrayList<Entry>()
        val problems = ArrayList<EnvironmentProblem>()
        var index = 0
        while (index < lines.size) {
            val number = index + 1
            val line = lines[index].trimStart()
            index++
            if (line.isEmpty() || line.startsWith('#')) continue
            fun problem(kind: EnvironmentProblemKind, variable: String? = null) {
                problems += EnvironmentProblem(file, number, kind, variable)
            }

            val words = line.split(Regex("\\s+"))
            if (words.first() == "unset" && words.size > 1) {
                val names = words.drop(1).takeWhile { !it.startsWith('#') }
                if (names.isEmpty() || names.any { !NAME.matches(it) }) {
                    problem(EnvironmentProblemKind.INVALID_NAME)
                    continue
                }
                val reserved = names.filter { isReserved(it) }
                reserved.forEach { problem(EnvironmentProblemKind.RESERVED_VARIABLE, it) }
                val kept = names - reserved.toSet()
                if (kept.isNotEmpty()) entries += Entry.Unset(number, kept)
                continue
            }

            val assignment = if (line.startsWith("export") && line.length > 6 && line[6].isWhitespace()) line.substring(7).trimStart() else line
            val equals = assignment.indexOf('=')
            if (equals < 0) {
                problem(EnvironmentProblemKind.INVALID_LINE)
                continue
            }
            val name = assignment.substring(0, equals).trimEnd()
            if (!NAME.matches(name)) {
                problem(EnvironmentProblemKind.INVALID_NAME)
                continue
            }
            val start = assignment.substring(equals + 1).trimStart()
            val value = try {
                when {
                    start.startsWith('\'') -> {
                        val quoted = quoted(start, lines, index, '\'') ?: throw Failure(EnvironmentProblemKind.UNTERMINATED_QUOTE)
                        index += quoted.extraLines
                        listOf(Part.Text(quoted.content))
                    }

                    start.startsWith('"') -> {
                        val quoted = quoted(start, lines, index, '"') ?: throw Failure(EnvironmentProblemKind.UNTERMINATED_QUOTE)
                        index += quoted.extraLines
                        expand(quoted.content, escapes = true)
                    }

                    else -> {
                        val comment = Regex("\\s#").find(start)?.range?.first
                        val bare = (if (comment == null) start else start.substring(0, comment)).trimEnd()
                        val home = if (bare == "~" || bare.startsWith("~/")) home else null
                        val parts = expand(if (home == null) bare else bare.substring(1), escapes = false)
                        if (home == null) parts else listOf(Part.Text(home)) + parts
                    }
                }
            } catch (failure: Failure) {
                problem(failure.kind)
                continue
            }
            if (isReserved(name)) {
                problem(EnvironmentProblemKind.RESERVED_VARIABLE, name)
                continue
            }
            entries += Entry.Assign(number, name, value)
        }
        return Parsed(entries, problems)
    }

    private class Quoted(val content: String, val extraLines: Int)

    // The text between the quote that starts [first] and the one that closes it, which may be on one of the [lines] from [next] on. What follows
    // it on its line must be blanks or a comment.
    private fun quoted(first: String, lines: List<String>, next: Int, quote: Char): Quoted? {
        val content = StringBuilder()
        var text = first.substring(1)
        var extra = 0
        while (true) {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (quote == '"' && c == '\\' && i + 1 < text.length) {
                    content.append(c).append(text[i + 1])
                    i += 2
                    continue
                }
                if (c == quote) {
                    val rest = text.substring(i + 1).trim()
                    if (rest.isNotEmpty() && !rest.startsWith('#')) throw Failure(EnvironmentProblemKind.INVALID_LINE)
                    return Quoted(content.toString(), extra)
                }
                content.append(c)
                i++
            }
            if (next + extra >= lines.size) return null
            content.append('\n')
            text = lines[next + extra]
            extra++
        }
    }

    // The parts of [text]: `${NAME}` and `$NAME` are references, and with [escapes] `\n`, `\t`, `\r`, `\"`, `\\` and `\$` are what they stand for.
    private fun expand(text: String, escapes: Boolean): List<Part> {
        val parts = ArrayList<Part>()
        val literal = StringBuilder()
        fun flush() {
            if (literal.isNotEmpty()) parts += Part.Text(literal.toString())
            literal.clear()
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                escapes && c == '\\' && i + 1 < text.length -> {
                    when (val next = text[i + 1]) {
                        'n' -> literal.append('\n')
                        't' -> literal.append('\t')
                        'r' -> literal.append('\r')
                        '"', '\\', '$' -> literal.append(next)
                        else -> literal.append(c).append(next)
                    }
                    i += 2
                }

                c == '$' && i + 1 < text.length && text[i + 1] == '{' -> {
                    val close = text.indexOf('}', i + 2)
                    if (close < 0) throw Failure(EnvironmentProblemKind.UNSUPPORTED_EXPANSION)
                    val name = text.substring(i + 2, close)
                    if (!NAME.matches(name)) throw Failure(EnvironmentProblemKind.UNSUPPORTED_EXPANSION)
                    flush()
                    parts += Part.Reference(name)
                    i = close + 1
                }

                c == '$' && i + 1 < text.length && text[i + 1] == '(' -> throw Failure(EnvironmentProblemKind.UNSUPPORTED_EXPANSION)

                c == '$' && i + 1 < text.length && (text[i + 1].isLetter() || text[i + 1] == '_') && text[i + 1].code < 128 -> {
                    var end = i + 1
                    while (end < text.length && (text[end] == '_' || (text[end].code < 128 && text[end].isLetterOrDigit()))) end++
                    flush()
                    parts += Part.Reference(text.substring(i + 1, end))
                    i = end
                }

                else -> {
                    literal.append(c)
                    i++
                }
            }
        }
        flush()
        return parts
    }
}

/** What the badge of an environment says: its [label], and whether no folder has it ([missing]), which its shells then start without. */
public data class EnvironmentBadge(val label: String, val missing: Boolean = false) {
    public companion object {
        /** The label of a workspace with no environment in a project that has one. */
        public const val STANDARD: String = "standard"
    }
}

private fun badgeOf(environment: String, available: Set<String>) = EnvironmentBadge(environment, environment !in available)

/** The badge of the project: its environment, none when it has none. [available] are the environments that exist. */
public fun Project.environmentBadge(available: Set<String>): EnvironmentBadge? = environment?.let { badgeOf(it, available) }

/**
 * The badge of the workspace in its [project]: none when it has the project's environment, [EnvironmentBadge.STANDARD] when it has none and its
 * project has one, else its own.
 */
public fun Workspace.environmentBadge(project: Project, available: Set<String>): EnvironmentBadge? = when (environment) {
    project.environment -> null
    null -> EnvironmentBadge(EnvironmentBadge.STANDARD)
    else -> badgeOf(environment, available)
}

/**
 * The badge of a terminal of the workspace, alone (a card of the board): the workspace's environment, [EnvironmentBadge.STANDARD] when it has
 * none and its [project] has one, and none when neither has one.
 */
public fun Workspace.terminalEnvironmentBadge(project: Project, available: Set<String>): EnvironmentBadge? = when {
    environment != null -> badgeOf(environment, available)
    project.environment != null -> EnvironmentBadge(EnvironmentBadge.STANDARD)
    else -> null
}

/** The environment of the workspace [id], `null` when it has none or there is no such workspace. */
public fun KattyState.environmentOf(id: WorkspaceId): String? =
    projects.firstNotNullOfOrNull { project -> project.workspaces.firstOrNull { it.id == id } }?.environment

/** The projects that have no environment: the ones that the settings' "Apply" gives the default one. */
public fun KattyState.projectsWithoutEnvironment(): List<Project> = projects.filter { it.environment == null }
