package app.katty.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OmpStoreTest {
    private val root: Path = Files.createTempDirectory("katty-omp")
    private val work: Path = Files.createDirectories(root.resolve("work")).toRealPath()
    private val sessions: Path = Files.createDirectories(root.resolve("sessions"))

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun store() = OmpStore(root)

    private val a = "01a15f51-3b4a-716b-9805-9c5af857d717"
    private val b = "01a15f68-e895-7281-9c21-4abd1f48cab2"

    // A session as omp writes it: the file's name has the start time and the id.
    private fun session(id: String, materialized: Boolean = true): Path {
        val file = sessions.resolve("2026-10-21T16-35-21-546Z_$id.jsonl")
        if (materialized) Files.writeString(file, """{"type":"session","version":3,"id":"$id","cwd":"$work"}""" + "\n")
        return file
    }

    // The breadcrumb as omp writes it: the directory, the session's file, `fresh` for a session with no file yet, and what it knows of the directory.
    private fun breadcrumb(terminal: String, cwd: String = work.toString(), file: Path, fresh: Boolean = false, modified: Long = 10_000_000): Path {
        val crumbs = Files.createDirectories(root.resolve("terminal-sessions")).resolve(terminal)
        Files.writeString(crumbs, "$cwd\n$file\n" + (if (fresh) "fresh\n" else "") + "cwdstat 16777234 184349763\n")
        Files.setLastModifiedTime(crumbs, FileTime.fromMillis(modified))
        return crumbs
    }

    @Test
    fun theSessionIsTheOneThatTheBreadcrumbOfTheTerminalNames() {
        breadcrumb("ttys011", file = session(a))
        breadcrumb("ttys017", file = session(b))

        // Two omps in one directory, each on its terminal.
        assertEquals(a, store().sessionFor(work.toString(), 9_000_000, "ttys011", listOf("omp")))
        assertEquals(b, store().sessionFor(work.toString(), 9_000_000, "ttys017", listOf("omp")))
    }

    @Test
    fun aFreshSessionIsNoSessionAndNothingIsGuessed() {
        // What `/new` leaves, or an omp that has not been told anything yet: the file is made at the first message.
        breadcrumb("ttys018", file = session(a, materialized = false), fresh = true)
        // The older session of the directory is there, and is not the terminal's.
        session(b)

        assertNull(store().sessionFor(work.toString(), 9_000_000, "ttys018", listOf("omp")))
    }

    @Test
    fun aBreadcrumbThatWasNotWrittenForThisProcessIsNotItsOwn() {
        breadcrumb("ttys011", file = session(a), modified = 1_000_000)

        // The process started after: it writes its breadcrumb when it starts, so this is the one of an omp that had the terminal before.
        assertNull(store().sessionFor(work.toString(), 9_000_000, "ttys011", listOf("omp")))
        // Told which session to open, it may have found the breadcrumb already as it would write it.
        assertEquals(a, store().sessionFor(work.toString(), 9_000_000, "ttys011", listOf("omp", "--resume=$a")))
        assertEquals(a, store().sessionFor(work.toString(), 9_000_000, "ttys011", listOf("omp", "-c")))
    }

    @Test
    fun theDirectoryIsComparedAsARealPath() {
        // omp writes the directory as it was given, and the system says the real one.
        val link = Files.createSymbolicLink(root.resolve("link"), work)
        breadcrumb("ttys011", cwd = link.toString(), file = session(a))

        assertEquals(a, store().sessionFor(work.toString(), 9_000_000, "ttys011", listOf("omp")))
        assertNull(store().sessionFor(root.toString(), 9_000_000, "ttys011", listOf("omp")))
    }

    @Test
    fun noTerminalNoBreadcrumbAndASessionFileThatIsGoneAreNoSession() {
        breadcrumb("ttys011", file = session(a))
        val gone = breadcrumb("ttys012", file = session(b, materialized = false))

        assertNull(store().sessionFor(work.toString(), 9_000_000, null, listOf("omp")))
        assertNull(store().sessionFor(work.toString(), 9_000_000, "ttys099", listOf("omp")))
        assertNull(store().sessionFor(work.toString(), 9_000_000, "../ttys011", listOf("omp")))
        assertNull(store().sessionFor(work.toString(), 9_000_000, "ttys012", listOf("omp")), gone.toString())
    }
}
