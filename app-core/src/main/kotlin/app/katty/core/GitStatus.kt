package app.katty.core

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The files of a working tree that are not as its last commit has them, counted as `git status` sees them. A file can be [staged] and [modified]
 * at once (a change added, then another one).
 */
public data class GitChanges(val staged: Int = 0, val modified: Int = 0, val untracked: Int = 0, val conflicted: Int = 0) {
    val isEmpty: Boolean get() = staged == 0 && modified == 0 && untracked == 0 && conflicted == 0

    public operator fun plus(other: GitChanges): GitChanges =
        GitChanges(staged + other.staged, modified + other.modified, untracked + other.untracked, conflicted + other.conflicted)
}

/**
 * What `git status` says of a working tree: its [changes], and how many commits its branch is [ahead] of its [upstream] (`origin/main`) and [behind]
 * it, `null` all three when it has no upstream (or is no branch).
 */
public data class GitStatusReading(val changes: GitChanges, val ahead: Int? = null, val behind: Int? = null, val upstream: String? = null)

/**
 * Reads what `git status --porcelain=v2 --branch` printed: `# branch.upstream` and `# branch.ab +N -M` for the upstream (the name without the
 * counts is an upstream that is gone, which is no upstream), a line for each file that is not clean (`1` and
 * `2`, whose two letters say what is staged and what is modified, `.` for nothing; `u`, unmerged; `?`, untracked), and nothing for the others.
 */
public fun parseGitStatus(output: String): GitStatusReading {
    var staged = 0
    var modified = 0
    var untracked = 0
    var conflicted = 0
    var ahead: Int? = null
    var behind: Int? = null
    var upstream: String? = null
    for (line in output.lineSequence()) {
        when {
            line.startsWith("# branch.upstream ") -> upstream = line.removePrefix("# branch.upstream ").trim().takeIf { it.isNotEmpty() }
            line.startsWith("# branch.ab ") -> for (part in line.removePrefix("# branch.ab ").split(' ')) {
                when {
                    part.startsWith('+') -> ahead = part.drop(1).toIntOrNull()
                    part.startsWith('-') -> behind = part.drop(1).toIntOrNull()
                }
            }
            (line.startsWith("1 ") || line.startsWith("2 ")) && line.length >= 4 -> {
                if (line[2] != '.') staged++
                if (line[3] != '.') modified++
            }
            line.startsWith("u ") -> conflicted++
            line.startsWith("? ") -> untracked++
        }
    }
    return GitStatusReading(GitChanges(staged, modified, untracked, conflicted), ahead, behind, upstream.takeIf { ahead != null || behind != null })
}

/**
 * Runs `git status` on a working tree. It is the user's own git, so that what is said is what `git status` says in a terminal: their
 * configuration, their file system monitor, their worktrees.
 */
public object GitStatusReader {
    /** How long a reading may take before it counts as none. */
    public const val TIMEOUT_MS: Long = 5_000

    @Volatile
    private var executable: String? = null

    @Volatile
    private var lookedFor = false

    /**
     * The `git` that the user's login shell finds (else the one of the application's `PATH`), or `null` when there is none that can be run.
     * `/usr/bin/git` is only a shim that opens the dialog that installs the developer tools of macOS when they are not there: it is only taken
     * when they are. It is looked for once the login shell's `PATH` is known, and kept.
     */
    public fun gitExecutable(): String? {
        if (lookedFor) return executable
        val loginPath = AgentDetection.knownLoginPath
        val found = findGit(loginPath ?: System.getenv("PATH").orEmpty())
        // Before the login shell's PATH is read, the application's may lack the user's git: look again once it is known.
        if (loginPath != null) {
            executable = found
            lookedFor = true
        }
        return found
    }

    /**
     * What `git status` says of the working tree at [tree], `null` when there is no git, it failed (not a repository) or it took longer than
     * [timeoutMs]. It is run with `--no-optional-locks`, so that it never writes the index: it cannot get in the way of the user's own git, and does
     * not change the files that are watched to know when to run it.
     */
    public fun read(tree: Path, runner: CommandRunner = ProcessCommandRunner, timeoutMs: Long = TIMEOUT_MS, git: String? = gitExecutable()): GitStatusReading? {
        git ?: return null
        val command = listOf(
            "/usr/bin/env", "GIT_OPTIONAL_LOCKS=0", git, "-c", "gc.auto=0", "--no-optional-locks", "status", "--porcelain=v2", "--branch",
        )
        val result = runner.run(command, tree, timeoutMs) ?: return null
        return if (result.exitCode == 0) parseGitStatus(result.output) else null
    }

    internal fun findGit(path: String, developerDirectory: () -> String? = ::developerDirectory): String? {
        val candidate = path.split(File.pathSeparatorChar).filter { it.isNotEmpty() }
            .map { Path.of(it).resolve("git") }
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
            ?: return null
        if (candidate.toString() != "/usr/bin/git") return candidate.toString()
        val tools = developerDirectory() ?: return null
        return candidate.toString().takeIf { Files.isExecutable(Path.of(tools, "usr", "bin", "git")) }
    }

    // What `xcode-select -p` says, the developer tools that `/usr/bin/git` runs; `null` when none are installed. It asks no question.
    private fun developerDirectory(): String? = try {
        val process = ProcessBuilder("/usr/bin/xcode-select", "-p").redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString().trim()
        if (process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0 && output.isNotEmpty()) output else null
    } catch (e: Exception) {
        null
    }
}
