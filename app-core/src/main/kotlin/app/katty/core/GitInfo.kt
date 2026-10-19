package app.katty.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** What a pane says of the repository its shell is in. */
public enum class GitKind {
    /** A checkout of a branch, in the main working tree. */
    BRANCH,

    /** A checkout in a linked working tree (`git worktree add`), which is drawn with its own icon. */
    WORKTREE,

    /** A checkout of a commit, which is no branch. */
    DETACHED,
}

/**
 * @param name The branch, or the first characters of the commit when [kind] is [GitKind.DETACHED].
 */
public data class GitInfo(val name: String, val kind: GitKind)

/**
 * The branch that every terminal of [workspace] is in, which is what the workspace says of itself: `null` when they are not all in the same one,
 * because one is not in a repository (or its place is not known yet), or they are in different branches, worktrees or commits. A workspace with
 * a single terminal has the one of that terminal.
 *
 * @param gits Where each terminal is.
 */
public fun Workspace.sharedGit(gits: Map<TerminalId, GitInfo?>): GitInfo? {
    val all = layout.terminals().map { gits[it.id] ?: return null }
    return all.firstOrNull()?.takeIf { first -> all.all { it == first } }
}

/**
 * Reads where a folder is in a git repository from the files of git, without starting it: it is read for every pane, again whenever its
 * shell changes directory and when a command ends.
 *
 * A linked working tree is told by its `.git` being a file that points into `worktrees/` of the repository's own; a submodule's is a file
 * too, and is a plain checkout here.
 */
public object GitReader {
    private const val COMMIT_CHARS = 7

    /** What [directory] is in, or `null` when it is not in a repository, or what is there cannot be read. */
    public fun read(directory: Path): GitInfo? {
        val dotGit = findDotGit(directory) ?: return null
        return try {
            if (Files.isDirectory(dotGit)) {
                head(dotGit, linked = false)
            } else {
                val pointer = Files.readString(dotGit).lineSequence().firstOrNull { it.startsWith("gitdir:") }?.removePrefix("gitdir:")?.trim() ?: return null
                val gitDir = dotGit.parent.resolve(pointer).normalize()
                head(gitDir, linked = gitDir.parent?.fileName?.toString() == "worktrees")
            }
        } catch (e: IOException) {
            null
        }
    }

    // The nearest `.git` above the folder (the folder itself included).
    private fun findDotGit(directory: Path): Path? {
        var current: Path? = directory.toAbsolutePath().normalize()
        while (current != null) {
            val candidate = current.resolve(".git")
            if (Files.exists(candidate)) return candidate
            current = current.parent
        }
        return null
    }

    private fun head(gitDir: Path, linked: Boolean): GitInfo? {
        val head = Files.readString(gitDir.resolve("HEAD")).trim()
        if (head.startsWith("ref:")) {
            val ref = head.removePrefix("ref:").trim()
            val name = ref.removePrefix("refs/heads/")
            return GitInfo(name, if (linked) GitKind.WORKTREE else GitKind.BRANCH)
        }
        return if (head.length >= COMMIT_CHARS) GitInfo(head.take(COMMIT_CHARS), GitKind.DETACHED) else null
    }
}
