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

/** What git is in the middle of in a working tree, which it says by the files it leaves in its git directory until it is over. */
public enum class GitOperation(public val label: String) {
    MERGE("merging"),
    REBASE("rebasing"),
    CHERRY_PICK("cherry-picking"),
    REVERT("reverting"),
    BISECT("bisecting"),
}

/**
 * Where a working tree is: its [root], the git directory that has its `HEAD` and its index ([gitDir]: the repository's `.git`, or
 * `.git/worktrees/<name>` for a linked working tree) and the one that its trees share ([commonDir]: the refs, the objects).
 */
public data class GitTree(val root: Path, val gitDir: Path, val commonDir: Path) {
    /** Whether its git directory is not in it, as a linked working tree's or a submodule's: watching the tree does not watch it. */
    val gitDirOutside: Boolean get() = !gitDir.startsWith(root)
}

/**
 * @param name The branch, or the first characters of the commit when [kind] is [GitKind.DETACHED]. During a rebase, the branch that is rebased.
 * @param operation What git is in the middle of, `null` when nothing.
 * @param tree The root of the working tree, which tells two terminals of one tree from two of different trees on the same branch.
 * @param status What `git status` says of the tree, `null` when it is not known (not read yet, no git, a tree that is too slow).
 */
public data class GitInfo(
    val name: String,
    val kind: GitKind,
    val operation: GitOperation? = null,
    val tree: String? = null,
    val status: GitStatusReading? = null,
)

/**
 * The branch that every terminal of [workspace] is in, which is what the workspace says of itself: `null` when they are not all in the same one,
 * because one is not in a repository (or its place is not known yet), or they are in different branches, worktrees or commits. A workspace with
 * a single terminal has the one of that terminal.
 *
 * Its changes are those of all its trees, each counted once however many terminals are in it (`null` when none is known). Where the branch is
 * against its upstream, and what git is in the middle of, are only said when all of its terminals are in one tree: two trees of one branch name
 * (two clones) may say different things.
 *
 * @param gits Where each terminal is.
 */
public fun Workspace.sharedGit(gits: Map<TerminalId, GitInfo?>): GitInfo? {
    val all = layout.terminals().map { gits[it.id] ?: return null }
    val first = all.firstOrNull() ?: return null
    if (!all.all { it.name == first.name && it.kind == first.kind }) return null
    val byTree = all.distinctBy { it.tree }
    if (byTree.size == 1) return first
    val changes = byTree.mapNotNull { it.status?.changes }.takeIf { it.isNotEmpty() }?.reduce(GitChanges::plus)
    return GitInfo(first.name, first.kind, status = changes?.let { GitStatusReading(it) })
}

/**
 * Where a terminal that is restarted starts, from [directory], where its shell last was: the root of [project] when it was in it (or in a folder
 * of it, a linked working tree that is inside it too), else the root of the linked working tree of the project's repository that it was in
 * ([GitReader.linkedWorktree]), else the root of the project. It reads a few files of git.
 */
public fun restartDirectory(project: String, directory: String): String {
    val projectPath = realPath(Path.of(project))
    val directoryPath = realPath(Path.of(directory))
    if (directoryPath.startsWith(projectPath)) return project
    return GitReader.linkedWorktree(projectPath, directoryPath)?.toString() ?: project
}

private fun realPath(path: Path): Path = try {
    path.toRealPath()
} catch (e: IOException) {
    path.toAbsolutePath().normalize()
}

/**
 * Reads where a folder is in a git repository from the files of git, without starting it: it is read for every pane, again whenever its
 * shell changes directory, when a command ends, and when the files of its working tree or of its git directory change. What `git status` says
 * is read by [GitStatusReader], which starts git.
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
                val gitDir = linkedGitDir(dotGit) ?: return null
                head(gitDir, linked = gitDir.parent?.fileName?.toString() == "worktrees")
            }
        } catch (e: IOException) {
            null
        }
    }

    /** The working tree that [directory] is in, `null` when it is in none (or a `.git` file points nowhere). */
    public fun locate(directory: Path): GitTree? = try {
        findDotGit(directory)?.let { dotGit ->
            val gitDir = if (Files.isDirectory(dotGit)) dotGit else linkedGitDir(dotGit)?.takeIf(Files::isDirectory)
            gitDir?.let { GitTree(realPath(dotGit.parent), realPath(it), commonDir(it)) }
        }
    } catch (e: IOException) {
        null
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
        val operation = operation(gitDir)
        val head = Files.readString(gitDir.resolve("HEAD")).trim()
        val branchKind = if (linked) GitKind.WORKTREE else GitKind.BRANCH
        if (head.startsWith("ref:")) {
            val ref = head.removePrefix("ref:").trim()
            return GitInfo(ref.removePrefix("refs/heads/"), branchKind, operation)
        }
        // A rebase detaches HEAD: the branch that it rebases is what the user is on.
        if (operation == GitOperation.REBASE) rebasedBranch(gitDir)?.let { return GitInfo(it, branchKind, operation) }
        return if (head.length >= COMMIT_CHARS) GitInfo(head.take(COMMIT_CHARS), GitKind.DETACHED, operation) else null
    }

    // What git leaves in the git directory while it is in the middle of something, in the order in which `git status` says them.
    private fun operation(gitDir: Path): GitOperation? = when {
        Files.exists(gitDir.resolve("MERGE_HEAD")) -> GitOperation.MERGE
        Files.isDirectory(gitDir.resolve("rebase-merge")) || Files.isDirectory(gitDir.resolve("rebase-apply")) -> GitOperation.REBASE
        Files.exists(gitDir.resolve("CHERRY_PICK_HEAD")) -> GitOperation.CHERRY_PICK
        Files.exists(gitDir.resolve("REVERT_HEAD")) -> GitOperation.REVERT
        Files.exists(gitDir.resolve("BISECT_LOG")) -> GitOperation.BISECT
        else -> null
    }

    // The branch that a rebase rebases (`refs/heads/x` in `head-name`), `null` for a rebase of a detached HEAD (`detached HEAD`).
    private fun rebasedBranch(gitDir: Path): String? =
        sequenceOf("rebase-merge", "rebase-apply").map { gitDir.resolve(it).resolve("head-name") }.firstOrNull(Files::isRegularFile)
            ?.let { Files.readString(it).trim() }
            ?.takeIf { it.startsWith("refs/heads/") }
            ?.removePrefix("refs/heads/")
}
