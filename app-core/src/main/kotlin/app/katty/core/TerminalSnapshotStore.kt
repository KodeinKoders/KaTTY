package app.katty.core

import app.katty.terminal.TerminalSnapshot
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

private val logger = LoggerFactory.getLogger(TerminalSnapshotStore::class.java)

// The most that a snapshot may be once it is read back, so that a file that was made for it (a compression bomb) does not fill the memory.
private const val MAX_SNAPSHOT_BYTES = 256 * 1024 * 1024

/**
 * What the terminals showed, kept in files for the next launch: one for each terminal, compressed, that only the user can read (a terminal's
 * history may hold anything that was typed or printed in it). A snapshot is what [app.katty.terminal.TerminalSession.snapshot] gives, which
 * the terminal that is started again is made from: its content comes back, and its program does not.
 *
 * What cannot be used is not an error: a file that is cut or damaged, or one that a **other build of libghostty** made (the format has no
 * promise of compatibility), is deleted and read as none. Nothing here throws for a problem with the files: not being able to save is not a
 * reason for the application to fail, and it is logged.
 *
 * Safe to call from several threads: each file is written whole under another name and moved in its place.
 *
 * @param directory Where the files are, made when something is saved.
 */
public class TerminalSnapshotStore(private val directory: Path) {
    // An id is the name of a file: only what cannot be taken for a path.
    private fun fileOf(id: TerminalId): Path =
        directory.resolve(id.value.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it.toString() else "%%%04x".format(it.code) }.joinToString("") + EXTENSION)

    /** Keeps [snapshot] as the one of the terminal [id], in place of the one it had. `false` when it could not be written. */
    public fun save(id: TerminalId, snapshot: TerminalSnapshot): Boolean {
        try {
            // Only the user's: before anything is in it, and not after.
            Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            val temporary = Files.createTempFile(directory, "snapshot", ".part", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            try {
                GZIPOutputStream(Files.newOutputStream(temporary)).use { it.write(snapshot.toByteArray()) }
                Files.move(temporary, fileOf(id), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Throwable) {
                Files.deleteIfExists(temporary)
                throw e
            }
            return true
        } catch (e: IOException) {
            logger.warn("Could not save the snapshot of {}", id.value, e)
            return false
        }
    }

    /** The snapshot of the terminal [id], or `null` when there is none, or it cannot be used (and it is then deleted). */
    public fun load(id: TerminalId): TerminalSnapshot? {
        val file = fileOf(id)
        if (!Files.isRegularFile(file)) return null
        return try {
            val bytes = GZIPInputStream(Files.newInputStream(file)).use { it.readNBytes(MAX_SNAPSHOT_BYTES + 1) }
            require(bytes.size <= MAX_SNAPSHOT_BYTES) { "It is more than $MAX_SNAPSHOT_BYTES bytes" }
            val snapshot = TerminalSnapshot.fromByteArray(bytes)
            if (snapshot.isRestorable) {
                snapshot
            } else {
                logger.info("The snapshot of {} was made by another build of libghostty: it is deleted", id.value)
                delete(id)
                null
            }
        } catch (e: Exception) {
            // Cut, damaged, or not ours (a ZipException is an IOException): a file that is no use is not kept.
            if (e !is IOException && e !is IllegalArgumentException) throw e
            logger.warn("The snapshot of {} cannot be read: it is deleted", id.value, e)
            delete(id)
            null
        }
    }

    /** Forgets the snapshot of [id]. */
    public fun delete(id: TerminalId) {
        try {
            Files.deleteIfExists(fileOf(id))
        } catch (e: IOException) {
            logger.warn("Could not delete the snapshot of {}", id.value, e)
        }
    }

    /** Forgets every snapshot but the ones of [ids]: the terminals that are gone do not leave their history behind. */
    public fun keepOnly(ids: Set<TerminalId>) {
        val keep = ids.map { fileOf(it).fileName.toString() }.toSet()
        forEachFile { if (it.fileName.toString() !in keep) deleteQuietly(it) }
    }

    /** Forgets every snapshot. */
    public fun clear() {
        forEachFile(::deleteQuietly)
    }

    private fun forEachFile(action: (Path) -> Unit) {
        if (!Files.isDirectory(directory)) return
        try {
            Files.newDirectoryStream(directory).use { files -> files.forEach { if (it.fileName.toString().let { name -> name.endsWith(EXTENSION) || name.endsWith(".part") }) action(it) } }
        } catch (e: IOException) {
            logger.warn("Could not list the snapshots", e)
        }
    }

    private fun deleteQuietly(file: Path) {
        try {
            Files.deleteIfExists(file)
        } catch (e: IOException) {
            logger.warn("Could not delete {}", file, e)
        }
    }

    private companion object {
        const val EXTENSION = ".snapshot"
    }
}
