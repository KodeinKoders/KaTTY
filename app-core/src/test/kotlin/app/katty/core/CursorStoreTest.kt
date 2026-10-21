package app.katty.core

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CursorStoreTest {
    private val root: Path = Files.createTempDirectory("katty-cursor")
    private val chats: Path = Files.createDirectories(root.resolve("chats"))
    private val work: Path = Files.createDirectories(root.resolve("work")).toRealPath()

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun store() = CursorStore(chats)

    private val a = "11a1677b-658c-48c7-9c10-d0bfa8ee6fb2"
    private val b = "e2ec4988-63d4-4d10-ba8d-9827c6bfbbd1"
    private val c = "520b9079-8bc2-4784-9dbd-4a37e62a2401"

    private fun md5(text: String) = MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    // A chat as Cursor writes it: a folder in the one of the directory, named by the MD5 of its path, with `meta.json`.
    private fun chat(id: String, created: Long, updated: Long = created, conversation: Boolean = false, subagent: Boolean = false, cwd: Path = work) {
        val folder = Files.createDirectories(chats.resolve(md5(cwd.toString())).resolve(id))
        Files.writeString(
            folder.resolve("meta.json"),
            """{"schemaVersion":1,"createdAtMs":$created,"hasConversation":$conversation,${if (conversation) "\"title\":\"Who Are You\"," else ""}"updatedAtMs":$updated${if (subagent) ",\"isSubagent\":true" else ""},"cwd":"$cwd"}""",
        )
    }

    private val agent = listOf("/Users/me/.local/bin/cursor-agent")

    @Test
    fun aNewCursorWithSomethingSaidIsInTheChatItMadeWhenItStarted() {
        chat(a, created = 1_000_000, conversation = true)
        chat(b, created = 9_000_300, updated = 9_500_000, conversation = true)

        assertEquals(b, store().sessionFor(work.toString(), 9_000_000, agent))
    }

    @Test
    fun aChatThatIsEmptyIsNoConversationAndNothingOlderIsPutInItsPlace() {
        // The chat that the program made when it started has had nothing said in it, and the one before it has a conversation.
        chat(a, created = 1_000_000, updated = 9_100_000, conversation = true)
        chat(b, created = 9_000_300)

        assertNull(store().sessionFor(work.toString(), 9_000_000, agent))
    }

    @Test
    fun aNewChatMadeAfterAnotherIsTheOneItIsInAtOnce() {
        // `/new-chat`: made when it is asked, empty, so there is nothing to come back on until something is said in it.
        chat(b, created = 9_000_300, updated = 9_800_000, conversation = true)
        chat(c, created = 9_700_000)

        assertNull(store().sessionFor(work.toString(), 9_000_000, agent))

        chat(c, created = 9_700_000, updated = 9_900_000, conversation = true)
        assertEquals(c, store().sessionFor(work.toString(), 9_000_000, agent))
    }

    @Test
    fun aResumedChatWithAConversationKeepsItsTimeSoItIsTheOneOfTheCommandLine() {
        chat(a, created = 1_000_000, updated = 9_100_000, conversation = true)
        chat(b, created = 2_000_000, updated = 2_000_000, conversation = true)

        assertEquals(a, store().sessionFor(work.toString(), 9_000_000, agent + listOf("--resume", a)))
        assertEquals(a, store().sessionFor(work.toString(), 9_000_000, agent + listOf("--force", "--resume=$a")))
        // A resumed chat that is empty has its time written anew, so it is the newest made, and empty.
        chat(b, created = 9_000_400)
        assertNull(store().sessionFor(work.toString(), 9_000_000, agent + listOf("--resume", b)))
    }

    @Test
    fun aContinueWithNoIdIsTheNewestChatOfTheDirectoryWithAConversationThatWasWrittenToSince() {
        chat(a, created = 1_000_000, updated = 9_100_000, conversation = true)
        chat(b, created = 2_000_000, updated = 9_300_000, conversation = true)
        chat(c, created = 3_000_000, updated = 9_500_000)

        assertEquals(b, store().sessionFor(work.toString(), 9_000_000, agent + "--continue"))
        assertEquals(b, store().sessionFor(work.toString(), 9_000_000, agent + "resume"))
        assertEquals(b, store().sessionFor(work.toString(), 9_000_000, agent + listOf("--resume")))
        // Not told to resume anything, the chats that it did not make are not its own.
        assertNull(store().sessionFor(work.toString(), 9_000_000, agent))
    }

    @Test
    fun aSubAgentsChatIsNotTheOnesOfTheTerminalAndAnotherDirectoryHasItsOwnFolder() {
        chat(a, created = 9_000_300, conversation = true, subagent = true)
        val other = Files.createDirectories(root.resolve("other")).toRealPath()
        chat(b, created = 9_000_300, conversation = true, cwd = other)

        assertNull(store().sessionFor(work.toString(), 9_000_000, agent))
        assertEquals(b, store().sessionFor(other.toString(), 9_000_000, agent))
    }

    @Test
    fun anIdThatThereIsNoChatOfNoFolderAndAFileThatIsNotAMetaAreNoChat() {
        assertNull(store().sessionFor(work.toString(), 0, agent + listOf("--resume", a)))
        assertNull(CursorStore(root.resolve("nowhere")).sessionFor(work.toString(), 0, agent))

        chat(b, created = 1_000_000, updated = 1_000_000, conversation = true)
        Files.writeString(chats.resolve(md5(work.toString())).resolve(b).resolve("meta.json"), "not json")
        assertNull(store().sessionFor(work.toString(), 0, agent + listOf("--resume", b)))
    }
}
