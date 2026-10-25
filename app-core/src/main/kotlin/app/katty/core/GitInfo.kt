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

    /**
     * The root of the linked working tree (`git worktree add`) that [directory] is in, when that tree is one of the repository that [project] is
     * in: [project] may be the repository's main working tree, a folder of it, or another of its linked trees. `null` when [directory] is in the
     * main working tree, in the tree that [project] is itself, in another repository, or in none.
     */
    public fun linkedWorktree(project: Path, directory: Path): Path? = try {
        val dotGit = findDotGit(directory)?.takeIf { Files.isRegularFile(it) }
        val gitDir = dotGit?.let(::linkedGitDir)
        when {
            dotGit == null || gitDir == null || gitDir.parent?.fileName?.toString() != "worktrees" -> null
            // The tree that the project is: it is the project.
            findDotGit(project)?.let(::realPath) == realPath(dotGit) -> null
            commonDir(gitDir) != findDotGit(project)?.let(::repositoryOf) -> null
            else -> dotGit.parent
        }
    } catch (e: IOException) {
        null
    }

    // The git directory that a `.git` file points to.
    private fun linkedGitDir(dotGit: Path): Path? {
        val pointer = Files.readString(dotGit).lineSequence().firstOrNull { it.startsWith("gitdir:") }?.removePrefix("gitdir:")?.trim() ?: return null
        return dotGit.parent.resolve(pointer).normalize()
    }

    // The repository's own git directory, which its linked trees share: `.git` itself, or what the git directory of a linked tree says in `commondir`.
    private fun repositoryOf(dotGit: Path): Path? =
        if (Files.isDirectory(dotGit)) realPath(dotGit) else linkedGitDir(dotGit)?.let(::commonDir)

    private fun commonDir(gitDir: Path): Path {
        val common = gitDir.resolve("commondir").takeIf(Files::isRegularFile)?.let { Files.readString(it).trim() }
        return realPath(if (common.isNullOrEmpty()) gitDir else gitDir.resolve(common).normalize())
    }

    private fun realPath(path: Path): Path = try {
        path.toRealPath()
    } catch (e: IOException) {
        path.toAbsolutePath().normalize()
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
