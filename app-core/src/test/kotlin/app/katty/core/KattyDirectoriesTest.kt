package app.katty.core

import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KattyDirectoriesTest {
    @AfterTest
    fun restore() {
        System.clearProperty(KattyDirectories.PROPERTY)
    }

    @Test
    fun theFilesAreInTheSupportDirectory() {
        val directories = KattyDirectories(Paths.get("/somewhere/KaTTY"))

        assertEquals(Paths.get("/somewhere/KaTTY/state.json"), directories.stateFile)
        assertEquals(Paths.get("/somewhere/KaTTY/usage"), directories.usageDirectory)
    }

    @Test
    fun thePropertyChangesTheDirectory() {
        System.setProperty(KattyDirectories.PROPERTY, "/tmp/katty-test")

        assertEquals(Paths.get("/tmp/katty-test"), KattyDirectories.default().support)
    }

    @Test
    fun byDefaultItIsTheApplicationSupportDirectoryOfTheUser() {
        val support = KattyDirectories.default().support

        assertTrue(support.startsWith(Paths.get(System.getProperty("user.home"))) || System.getenv("XDG_DATA_HOME") != null)
        assertEquals("KaTTY", support.fileName.toString())
    }
}
