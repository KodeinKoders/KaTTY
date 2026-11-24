package app.katty.terminal

import java.io.File

/**
 * The file system of a POSIX environment of Windows (MSYS2 and Git for Windows, which is built on it, or Cygwin), as its shells see it: a drive is a
 * folder under [cygdrive] (`/c/Users/me` for MSYS2, `/cygdrive/c/Users/me` for Cygwin), the system's own folders are under [root] (`/home/me` is
 * `C:\msys64\home\me`), and [mounts] say where other folders are (Git for Windows' `/tmp` is the user's temporary folder).
 *
 * KaTTY gives its scripts to these shells with paths of theirs ([toPosix]), and reads the directory that they report ([toWindows]). It converts
 * them itself and starts no `cygpath`: a process costs much there, more so when the environment is x64 and runs emulated on ARM64.
 *
 * @property root The folder of Windows that is `/`.
 * @property cygdrive Where the drives are, with a slash at its end: `/` or `/cygdrive/`.
 * @property mounts Other folders, by their POSIX path (no slash at its end), the longest first.
 */
internal class PosixFileSystem(val root: String, val cygdrive: String, val mounts: List<Pair<String, String>> = emptyList()) {
    /** [windows] (`C:\Users\me`, `\\server\share`) as these shells write it (`/c/Users/me`, `//server/share`). */
    fun toPosix(windows: String): String {
        val path = windows.replace('\\', '/')
        DRIVE.matchEntire(path)?.let { match -> return cygdrive + match.groupValues[1].lowercase() + match.groupValues[2] }
        return path
    }

    /**
     * [posix] (`/c/Users/me`, `/cygdrive/c/Users/me`, `/home/me`, `/tmp/x`) as Windows writes it (`C:\Users\me`), `null` for a path that is not
     * absolute.
     */
    fun toWindows(posix: String): String? {
        if (!posix.startsWith("/")) return null
        if (posix.startsWith("//")) return posix.replace('/', '\\')
        if (posix.startsWith(cygdrive)) {
            DRIVE_FOLDER.matchEntire(posix.removePrefix(cygdrive))?.let { match ->
                return match.groupValues[1].uppercase() + ":\\" + match.groupValues[2].trimStart('/').replace('/', '\\')
            }
        }
        for ((mount, folder) in mounts) {
            if (posix == mount || posix.startsWith("$mount/")) return folder.trimEnd('\\') + posix.removePrefix(mount).replace('/', '\\')
        }
        return root.trimEnd('\\') + posix.replace('/', '\\')
    }

    override fun toString(): String = "PosixFileSystem(root=$root, cygdrive=$cygdrive, mounts=$mounts)"

    companion object {
        // `C:/rest` or `C:`: its letter, and the rest of the path.
        private val DRIVE = Regex("([A-Za-z]):(/.*)?")

        // `c/rest` or `c` under the drives' folder.
        private val DRIVE_FOLDER = Regex("([A-Za-z])(/.*)?")

        /**
         * The file system of the environment that the program at [program] belongs to, `null` when it is not one: a program of MSYS2 has
         * `msys-2.0.dll` in its folder, one of Cygwin `cygwin1.dll`. Its root is the folder above `usr\bin` (MSYS2) or `bin` (Cygwin, and the
         * launcher of Git for Windows, whose DLL is in `..\usr\bin`), and its `etc\fstab` may move the drives (`none /mnt cygdrive`) and mount
         * folders (`C:/work /work ntfs`, or `none /tmp usertemp`: the user's temporary folder, [temporary]).
         */
        fun of(program: String, exists: (String) -> Boolean = { File(it).isFile }, read: (String) -> List<String>? = ::readLines, temporary: String? = System.getenv("TEMP")): PosixFileSystem? {
            val folder = program.replace('/', '\\').substringBeforeLast('\\', "")
            if (folder.isEmpty()) return null
            val (root, msys) = when {
                exists("$folder\\msys-2.0.dll") -> folder.removeSuffix("\\usr\\bin").removeSuffix("\\bin") to true
                exists("$folder\\..\\usr\\bin\\msys-2.0.dll") -> folder.substringBeforeLast('\\') to true
                exists("$folder\\cygwin1.dll") -> folder.removeSuffix("\\bin") to false
                else -> return null
            }
            var cygdrive = if (msys) "/" else "/cygdrive/"
            // What both mount by themselves: the programs of MSYS2 are in `usr\bin` and also `/bin`, Cygwin's in `bin` and also `/usr/bin`.
            val mounts = if (msys) arrayListOf("/bin" to "$root\\usr\\bin") else arrayListOf("/usr/bin" to "$root\\bin", "/usr/lib" to "$root\\lib")
            for (line in read("$root\\etc\\fstab").orEmpty()) {
                val fields = line.substringBefore('#').trim().split(Regex("\\s+"))
                if (fields.size < 3) continue
                val (source, mount, type) = fields
                val point = unescape(mount).trimEnd('/').ifEmpty { "/" }
                when {
                    type == "cygdrive" -> cygdrive = if (point == "/") "/" else "$point/"
                    type == "usertemp" -> temporary?.let { mounts += point to it }
                    source != "none" -> mounts += point to unescape(source).replace('/', '\\')
                }
            }
            return PosixFileSystem(root, cygdrive, mounts.sortedByDescending { it.first.length })
        }

        // A space is `\040` in an fstab.
        private fun unescape(field: String) = field.replace("\\040", " ")

        private fun readLines(path: String): List<String>? = File(path).takeIf { it.isFile }?.readLines()
    }
}

/**
 * The shell that the program of a session is, as KaTTY knows it (see [TerminalSession.shell]): what a text for its command line is written for.
 *
 * @property name `zsh`, `bash`, `fish`, `pwsh` or `powershell`.
 * @property writePath How it writes a path of this machine: as it is, or the way of its POSIX environment on Windows (`/c/Users/me`).
 */
public class TerminalShell(public val name: String, private val writePath: (String) -> String = { it }) {
    /** Whether it is PowerShell, whose quotes are not a POSIX shell's. */
    public val isPowerShell: Boolean get() = name == "pwsh" || name == "powershell"

    /** [path] of this machine as this shell writes it. */
    public fun path(path: String): String = writePath(path)

    override fun toString(): String = "TerminalShell($name)"
}
