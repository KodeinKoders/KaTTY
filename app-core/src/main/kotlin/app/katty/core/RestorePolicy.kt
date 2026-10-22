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
     * connect ([onlyConnects]): a command after the host would run on the other machine again.
     */
    public fun runs(argv: List<String>): Boolean {
        val program = argv.firstOrNull()?.let { File(it).name } ?: return false
        return when {
            program in NEVER -> false
            Agent.entries.any { it.executable == program } -> true
            program in REPLS -> argv.size == 1
            program == "ssh" -> onlyConnects(argv.drop(1), SSH_FLAGS, SSH_VALUE_OPTIONS)
            program == "mosh" -> onlyConnects(argv.drop(1), MOSH_FLAGS, MOSH_VALUE_OPTIONS)
            else -> program in runnable
        }
    }

    public companion object {
        private val NEVER = setOf("sudo", "doas", "su")

        private val REPLS = setOf("python", "python3", "ipython", "node", "irb", "ghci", "lua", "sqlite3", "psql", "mysql", "redis-cli")

        // ssh's options (`man ssh`): the letters that are flags, and the ones that take a value, attached (`-p22`) or as the next word.
        // `-O` (a command for the master of a shared connection) is left out: it does not connect, it runs.
        private val SSH_FLAGS = "46AaCfGgKkMNnqsTtVvXxYy".toSet()
        private val SSH_VALUE_OPTIONS = "BbcDEeFIiJLlmoPpQRSWw".toSet()

        // mosh's options that are safe to give again; `--ssh=` and `--server=` are commands (on this machine and on the other) and are not.
        private val MOSH_FLAGS = setOf("-a", "-n", "-4", "-6", "--predict-overwrite", "--no-init", "--no-ssh-pty", "--local")
        private val MOSH_VALUE_OPTIONS = setOf("--port", "--predict", "--family", "--bind-server", "--experimental-remote-ip")

        // The options of ssh's configuration (`-o`) that run a command, on this machine or on the other.
        private val SSH_COMMAND_OPTIONS = setOf("proxycommand", "localcommand", "permitlocalcommand", "remotecommand")

        /**
         * Whether the arguments of `ssh` only connect to a host: options that are known (and none that runs a command), then the host, and
         * nothing after it. An option that is not known cannot be told from one that takes the next word, so it is typed only.
         */
        private fun onlyConnects(arguments: List<String>, flags: Set<Char>, valueOptions: Set<Char>): Boolean {
            var index = 0
            while (index < arguments.size) {
                val word = arguments[index]
                if (word == "--") {
                    index++
                    break
                }
                if (!word.startsWith("-") || word == "-") break
                // Grouped letters (`-At`), the last of which may take a value (`-Ai key` or `-Aikey`).
                var letter = 1
                while (letter < word.length) {
                    val option = word[letter]
                    when (option) {
                        in flags -> letter++
                        in valueOptions -> {
                            val value = word.substring(letter + 1).ifEmpty { arguments.getOrNull(++index) ?: return false }
                            if (option == 'o' && value.split('=', ' ', '\t', limit = 2).first().lowercase() in SSH_COMMAND_OPTIONS) return false
                            letter = word.length
                        }
                        else -> return false
                    }
                }
                index++
            }
            val rest = arguments.drop(index)
            return rest.size == 1 && !rest.single().startsWith("-")
        }

        /** The same for `mosh`, whose options are words: `-p` and the long ones with an `=`. */
        @JvmName("onlyConnectsWords")
        private fun onlyConnects(arguments: List<String>, flags: Set<String>, valueOptions: Set<String>): Boolean {
            var index = 0
            while (index < arguments.size) {
                val word = arguments[index]
                when {
                    word == "--" -> { index++; break }
                    !word.startsWith("-") -> break
                    word in flags -> index++
                    word == "-p" -> { if (index + 1 >= arguments.size) return false; index += 2 }
                    word.substringBefore('=') in valueOptions && '=' in word -> index++
                    else -> return false
                }
            }
            val rest = arguments.drop(index)
            return rest.size == 1 && !rest.single().startsWith("-")
        }

        /** Editors, pagers, monitors, file managers and terminal multiplexers: they show something, or wait for the user. */
        public val DEFAULT_RUNNABLE: Set<String> = setOf(
            "emacs", "emacsclient", "vim", "nvim", "vi", "view", "nano", "micro", "hx", "helix", "kak", "less", "more", "most", "bat", "man",
            "top", "htop", "btop", "atop", "glances", "tail", "tmux", "screen", "zellij", "lazygit", "tig", "k9s", "ranger", "mc", "yazi", "nnn",
        )
    }
}
