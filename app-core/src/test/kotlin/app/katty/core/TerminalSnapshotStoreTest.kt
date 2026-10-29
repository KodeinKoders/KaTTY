package app.katty.core

import app.katty.terminal.TerminalSession
import app.katty.terminal.TerminalSnapshot
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalSnapshotStoreTest {
    private val directory = Files.createTempDirectory("katty-snapshots").resolve("snapshots")
    private val store = TerminalSnapshotStore(directory)
    private val sessions = mutableListOf<TerminalSession>()
    private val id = TerminalId("t-1")

    @AfterTest
    fun close() {
        sessions.forEach { it.close() }
        directory.parent.toFile().deleteRecursively()
    }

    // A terminal that has printed something, and waits.
    private fun snapshot(text: String = "hello"): TerminalSnapshot {
        val session = TerminalSession.start(40, 5, command = listOf("/bin/sh", "-c", "echo $text; sleep 30"), shellIntegration = false).also { sessions += it }
        val deadline = System.nanoTime() + 15_000_000_000L
        while (text !in session.viewportText()) {
            check(System.nanoTime() < deadline) { "Still waiting for $text" }
            Thread.sleep(10)
        }
        return session.snapshot()
    }

    @Test
    fun aSnapshotIsKeptAndComesBackWithItsContent() {
        val saved = snapshot()

        assertTrue(store.save(id, saved))
        val loaded = assertNotNull(store.load(id))

        assertEquals(saved.cols, loaded.cols)
        assertEquals(saved.rows, loaded.rows)
        assertEquals(saved.sizeBytes, loaded.sizeBytes)
        assertEquals(saved.toByteArray().toList(), loaded.toByteArray().toList())
    }

    @Test
    fun nothingIsReadWhereNothingWasSaved() {
        assertNull(store.load(id))
        assertFalse(Files.exists(directory), "Not even the directory is made by a read")
    }

    @Test
    fun theFilesAreForTheUserOnly() {
        store.save(id, snapshot())

        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)))
        val file = Files.list(directory).use { it.toList().single() }
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
    }

    @Test
    fun savingAgainReplacesTheSnapshotAndLeavesNoTemporaryFile() {
        store.save(id, snapshot("first"))
        val second = snapshot("second")

        store.save(id, second)

        assertEquals(second.toByteArray().toList(), store.load(id)!!.toByteArray().toList())
        assertEquals(1, Files.list(directory).use { it.count() }, "One file for the terminal, and no .part left")
    }

    @Test
    fun aFileThatIsDamagedOrCutIsDeletedAndReadAsNone() {
        store.save(id, snapshot())
        val file = Files.list(directory).use { it.toList().single() }

        // Cut in the middle of its compression.
        Files.write(file, Files.readAllBytes(file).copyOf(30))
        assertNull(store.load(id))
        assertFalse(Files.exists(file), "A file that is no use is not kept")

        // Not compressed at all.
        Files.writeString(file, "not a snapshot")
        assertNull(store.load(id))
        assertFalse(Files.exists(file))

        // Compressed, and not a snapshot.
        GZIPOutputStream(Files.newOutputStream(file)).use { it.write("KATTYSNPnonsense".toByteArray()) }
        assertNull(store.load(id))
        assertFalse(Files.exists(file))
    }

    @Test
    fun aSnapshotOfAnotherBuildOfLibghosttyIsDeletedAndReadAsNone() {
        val snapshot = snapshot()
        store.save(id, snapshot)
        val file = Files.list(directory).use { it.toList().single() }
        // The same snapshot, saying that another library made it: its fingerprint is the 64 characters after the magic and the version.
        val bytes = snapshot.toByteArray()
        val fingerprint = String(bytes, 8 + 2 + 2, 64, Charsets.US_ASCII)
        val other = String(bytes, Charsets.ISO_8859_1).replace(fingerprint, "0".repeat(64)).toByteArray(Charsets.ISO_8859_1)
        GZIPOutputStream(Files.newOutputStream(file)).use { it.write(other) }
        assertFalse(TerminalSnapshot.fromByteArray(other).isRestorable)

        assertNull(store.load(id))

        assertFalse(Files.exists(file))
    }

    @Test
    fun theIdOfATerminalIsNeverAPath() {
        val hostile = TerminalId("../../escape")

        store.save(hostile, snapshot())

        assertEquals(1, Files.list(directory).use { it.count() })
        assertFalse(Files.exists(directory.parent.parent.resolve("escape.snapshot")))
        assertNotNull(store.load(hostile))
    }

    @Test
    fun onlyTheSnapshotsOfTheTerminalsThatAreKeptStay() {
        val one = snapshot()
        store.save(TerminalId("a"), one)
        store.save(TerminalId("b"), one)
        store.save(TerminalId("c"), one)

        store.keepOnly(setOf(TerminalId("b")))

        assertNull(store.load(TerminalId("a")))
        assertNotNull(store.load(TerminalId("b")))
        assertNull(store.load(TerminalId("c")))

        store.clear()
        assertNull(store.load(TerminalId("b")))
    }

    @Test
    fun deletingWhatIsNotThereIsNothing() {
        store.delete(id)
        store.keepOnly(emptySet())
        store.clear()
    }
}
