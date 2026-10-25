package app.katty.ghostty

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryExtractionTest {
    private val parent: Path = Files.createTempDirectory("katty-extraction-test")
    private val bytes = ByteArray(10_000) { (it * 31).toByte() }
    private val hash = sha256(bytes)

    @Test
    fun theLibraryIsExtractedOnceToAFolderNamedByWhatItIs() {
        val first = extractLibrary(bytes, hash, parent, "libghostty-vt.dylib")
        val modified = Files.getLastModifiedTime(first)

        val second = extractLibrary(bytes, hash, parent, "libghostty-vt.dylib")

        assertEquals(first, second)
        assertTrue(first.parent.fileName.toString().startsWith("katty-ghostty-${hash.take(16)}"), first.toString())
        assertEquals(modified, Files.getLastModifiedTime(second), "It was written again")
        assertContentEquals(bytes, second.readBytes())
        assertEquals(listOf(first.fileName), Files.list(first.parent).use { it.toList() }.map { it.fileName }, "A temporary file was left")
    }

    @Test
    fun aCopyThatIsNotTheLibraryAnyMoreIsReplaced() {
        val file = extractLibrary(bytes, hash, parent, "libghostty-vt.dylib")
        file.writeBytes(byteArrayOf(1, 2, 3))

        val again = extractLibrary(bytes, hash, parent, "libghostty-vt.dylib")

        assertContentEquals(bytes, again.readBytes())
    }

    @Test
    fun theFoldersThatEarlierVersionsLeftAreRemovedAndNothingElse() {
        val old = parent.resolve("katty-ghostty1234567890").createDirectories()
        old.resolve("libghostty-vt.dylib").writeBytes(bytes)
        val current = extractLibrary(bytes, hash, parent, "libghostty-vt.dylib").parent
        val other = parent.resolve("katty-ghostty-notes").createDirectories()
        val unrelated = parent.resolve("something-else123").createDirectories()

        removeLeftoverExtractions(parent)

        assertFalse(old.exists())
        assertTrue(current.exists())
        assertTrue(other.exists())
        assertTrue(unrelated.exists())
    }
}
