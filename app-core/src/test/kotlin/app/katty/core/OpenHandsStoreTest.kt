package app.katty.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenHandsStoreTest {
    private val root: Path = Files.createTempDirectory("katty-openhands")
    private val conversations: Path = Files.createDirectories(root.resolve("conversations"))
    private val work: Path = Files.createDirectories(root.resolve("work")).toRealPath()

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun store() = OpenHandsStore(conversations)

    private val a = "22a2ff2b1255419b96365045c8977380"
    private val b = "5dfff0c299bc4d5ea6bd2c96a13f677b"
    private val c = "c0ebe5bbbf6e4300bf02312b28b4506f"

    private fun dashed(hex: String) = "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"

    // A conversation as OpenHands writes it: a folder named by its id in hexadecimal, with its state and its events, the first of which is the system prompt.
    private fun conversation(id: String, cwd: Path = work, created: Long, written: Long = created, events: Boolean = true): Path {
        val folder = Files.createDirectories(conversations.resolve(id))
        Files.writeString(folder.resolve("base_state.json"), """{"id":"${dashed(id)}","agent":{"llm":{"model":"anthropic/x"}},"workspace":{"working_dir":"$cwd","kind":"LocalWorkspace"},"stats":{}}""")
        if (events) {
            val first = Files.createDirectories(folder.resolve("events")).resolve("event-00000-f850f836-17f2-4fa9-91c0-aa960c3489ce.json")
            Files.writeString(first, """{"kind":"SystemPromptEvent","timestamp":"2026-10-21T19:36:00.992300"}""")
            Files.setLastModifiedTime(first, FileTime.fromMillis(created))
        }
        Files.setLastModifiedTime(folder, FileTime.fromMillis(written))
        return folder
    }

    @Test
    fun aNewOpenHandsIsInTheConversationThatWasMadeSinceItStarted() {
        conversation(b, created = 1_000_000)
        conversation(a, created = 9_500_000)

        assertEquals(dashed(a), store().sessionFor(work.toString(), 9_000_000, listOf("openhands")))
    }

    @Test
    fun theNewestConversationMadeSinceItStartedIsTheOneItIsInAfterANewOne() {
        // `/new`: another conversation, made when its first message was sent.
        conversation(a, created = 9_500_000, written = 9_900_000)
        conversation(c, created = 9_800_000)

        assertEquals(dashed(c), store().sessionFor(work.toString(), 9_000_000, listOf("openhands")))
    }

    @Test
    fun aResumedOpenHandsIsInTheConversationOfItsCommandLineWhenItHasNotStartedAnother() {
        conversation(b, created = 1_000_000, written = 9_100_000)
        conversation(a, created = 2_000_000, written = 2_000_000)

        assertEquals(dashed(b), store().sessionFor(work.toString(), 9_000_000, listOf("openhands", "--resume", dashed(b))))
        assertEquals(dashed(b), store().sessionFor(work.toString(), 9_000_000, listOf("openhands", "--always-approve", "--resume=${dashed(b)}")))
        // It started another since: that one.
        conversation(c, created = 9_800_000)
        assertEquals(dashed(c), store().sessionFor(work.toString(), 9_000_000, listOf("openhands", "--resume", dashed(b))))
    }

    @Test
    fun aResumeWithNoIdIsTheNewestConversationOfTheDirectoryThatWasWrittenToSinceAndNoMoreThanThat() {
        conversation(b, created = 1_000_000, written = 9_100_000)
        conversation(a, created = 2_000_000, written = 9_300_000)

        assertEquals(dashed(a), store().sessionFor(work.toString(), 9_000_000, listOf("openhands", "--resume", "--last")))
        assertEquals(dashed(a), store().sessionFor(work.toString(), 9_000_000, listOf("openhands", "--resume")))
        // Not told to resume anything: the conversations that it did not make are not its own.
        assertNull(store().sessionFor(work.toString(), 9_000_000, listOf("openhands")))
    }

    @Test
    fun anOpenHandsThatHasBeenToldNothingYetHasNoConversation() {
        conversation(b, created = 1_000_000, written = 1_000_000)

        assertNull(store().sessionFor(work.toString(), 9_000_000, listOf("openhands")))
        assertNull(store().sessionFor(work.toString(), 9_000_000, listOf("openhands", "--always-approve")))
    }

    @Test
    fun aConversationOfAnotherDirectoryOrWithNoEventIsNotTheOnesOfThisProcess() {
        val other = Files.createDirectories(root.resolve("other")).toRealPath()
        conversation(a, cwd = other, created = 9_500_000)
        conversation(c, created = 9_600_000, events = false)

        assertNull(store().sessionFor(work.toString(), 9_000_000, listOf("openhands")))
        assertEquals(dashed(a), store().sessionFor(other.toString(), 9_000_000, listOf("openhands")))
    }

    @Test
    fun anIdThatThereIsNoConversationOfAndNoFolderAreNoConversation() {
        assertNull(store().sessionFor(work.toString(), 0, listOf("openhands", "--resume", dashed(a))))

        conversation(b, created = 1_000_000, written = 9_100_000)
        assertNull(store().sessionFor(work.toString(), 9_000_000, listOf("openhands", "--resume", dashed(a))))
        assertNull(OpenHandsStore(root.resolve("nowhere")).sessionFor(work.toString(), 0, listOf("openhands")))
    }
}
