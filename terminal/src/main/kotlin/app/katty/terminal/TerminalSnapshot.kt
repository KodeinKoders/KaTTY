package app.katty.terminal

import app.katty.ghostty.GhosttyBuildInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

/** A snapshot could not be made, or cannot be restored. */
public class TerminalSnapshotException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * What a terminal was: its screen and its whole history, where its program was and its title, to be kept and restored as a new session
 * ([TerminalSession.start] with `snapshot`). Made by [TerminalSession.snapshot].
 *
 * A process cannot be saved: restoring is a **new program**, by default the user's shell, in the directory that the old one was in, which
 * starts below what the old terminal showed.
 *
 * **It is good for the build that made it.** libghostty's format has no promise of compatibility, so a snapshot knows which library made it
 * and is refused by another ([isRestorable]): the host starts a new session. It is not an archive.
 *
 * It does not have the images of the Kitty protocol, the selection, nor the scrolling of the viewport. It is as big as the history is, and not
 * compressed: a host that keeps many should compress [toByteArray] itself.
 *
 * @param cols How wide the terminal was.
 * @param rows How high.
 * @param title The title that the program had set, empty if it had not.
 * @param workingDirectory Where the shell was (it has to report it: see `shellIntegration` of [TerminalSession.start]), a path, or `null`.
 * @param createdAt When it was made, in milliseconds since the epoch.
 * @param fingerprint Which libghostty made it.
 */
public class TerminalSnapshot internal constructor(
    public val cols: Int,
    public val rows: Int,
    public val title: String,
    public val workingDirectory: String?,
    public val createdAt: Long,
    public val fingerprint: String,
    internal val data: ByteArray,
) {
    /** Whether the library that is loaded can restore it: the one that made it. */
    public val isRestorable: Boolean get() = fingerprint == GhosttyBuildInfo.fingerprint()

    /** What [toByteArray] has, in bytes, which is about what it takes to keep. */
    public val sizeBytes: Int get() = data.size

    /** The snapshot as bytes to keep, which [fromByteArray] reads. */
    public fun toByteArray(): ByteArray {
        val bytes = ByteArrayOutputStream(data.size + 256)
        DataOutputStream(bytes).use { out ->
            out.write(MAGIC)
            out.writeShort(VERSION)
            out.writeUTF(fingerprint)
            out.writeLong(createdAt)
            out.writeInt(cols)
            out.writeInt(rows)
            out.writeUTF(title.take(MAX_TEXT))
            out.writeBoolean(workingDirectory != null)
            if (workingDirectory != null) out.writeUTF(workingDirectory.take(MAX_TEXT))
            out.writeInt(data.size)
            out.write(data)
        }
        return bytes.toByteArray()
    }

    override fun toString(): String = "TerminalSnapshot(${cols}x$rows, title=$title, workingDirectory=$workingDirectory, $sizeBytes bytes)"

    public companion object {
        private val MAGIC = "KATTYSNP".toByteArray(Charsets.US_ASCII)
        private const val VERSION = 1
        private const val MAX_TEXT = 4096

        /**
         * The snapshot that [toByteArray] gave. It does not check that it can be restored by this library: [isRestorable] says.
         *
         * @throws IllegalArgumentException If [bytes] is not one of ours, or is cut.
         */
        public fun fromByteArray(bytes: ByteArray): TerminalSnapshot {
            try {
                val input = DataInputStream(ByteArrayInputStream(bytes))
                val magic = ByteArray(MAGIC.size)
                input.readFully(magic)
                require(magic.contentEquals(MAGIC)) { "It is not a snapshot of KaTTY" }
                val version = input.readUnsignedShort()
                require(version == VERSION) { "It is a snapshot of version $version, and this reads $VERSION" }
                val fingerprint = input.readUTF()
                val createdAt = input.readLong()
                val cols = input.readInt()
                val rows = input.readInt()
                val title = input.readUTF()
                val workingDirectory = if (input.readBoolean()) input.readUTF() else null
                val size = input.readInt()
                require(size in 0..input.available()) { "It is cut: $size bytes of terminal, ${input.available()} left" }
                val data = ByteArray(size).also { input.readFully(it) }
                require(cols in 1..0xFFFF && rows in 1..0xFFFF) { "Its size is not one: ${cols}x$rows" }
                return TerminalSnapshot(cols, rows, title, workingDirectory, createdAt, fingerprint, data)
            } catch (e: EOFException) {
                throw IllegalArgumentException("It is cut", e)
            } catch (e: IOException) {
                throw IllegalArgumentException("It cannot be read: ${e.message}", e)
            }
        }
    }
}
