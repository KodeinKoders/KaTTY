package app.katty.core

import java.io.File

/**
 * Which programs a restored terminal runs by itself, and which it only types and leaves for the user to run: starting a program again
 * is the same as typing its command, and a command that does something (a deployment, a deletion, a push) must not run because the
 * application was opened. The list is of programs that are safe to start again: they show something or wait for the user.
 *
 * @param extra More programs (their names) that the user wants started again, besides [DEFAULT_RUNNABLE].
 */
public class RestorePolicy(extra: Set<String> = emptySet()) {
    private val runnable: Set<String> = DEFAULT_RUNNABLE + extra

    /**
     * Whether [argv] is started again by itself. The program is judged by its name alone, so `env X=1 emacs` or `time make` are typed, and
     * `sudo`, `doas` and `su` never run. A REPL runs when it has no argument (with one it runs a script), and `ssh` and `mosh` when they only
     * name a host: a command after it would run on the other machine again.
     */
    public fun runs(argv: List<String>): Boolean {
        val program = argv.firstOrNull()?.let { File(it).name } ?: return false
        return when {
            program in NEVER -> false
            Agent.entries.any { it.executable == program } -> true
            program in REPLS -> argv.size == 1
            program in REMOTE -> argv.size <= 4 && argv.drop(1).none { it.any(Char::isWhitespace) }
            else -> program in runnable
        }
    }

    public companion object {
        private val NEVER = setOf("sudo", "doas", "su")

        private val REPLS = setOf("python", "python3", "ipython", "node", "irb", "ghci", "lua", "sqlite3", "psql", "mysql", "redis-cli")

        private val REMOTE = setOf("ssh", "mosh")

        /** Editors, pagers, monitors, file managers and terminal multiplexers: they show something, or wait for the user. */
        public val DEFAULT_RUNNABLE: Set<String> = setOf(
            "emacs", "emacsclient", "vim", "nvim", "vi", "view", "nano", "micro", "hx", "helix", "kak", "less", "more", "most", "bat", "man",
            "top", "htop", "btop", "atop", "glances", "tail", "tmux", "screen", "zellij", "lazygit", "tig", "k9s", "ranger", "mc", "yazi", "nnn",
        )
    }
}
