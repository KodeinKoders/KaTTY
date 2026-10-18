package app.katty.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RestorePolicyTest {
    private val policy = RestorePolicy()

    @Test
    fun runsEditorsPagersMonitorsAndAgents() {
        assertTrue(policy.runs(listOf("emacs", "notes.txt")))
        assertTrue(policy.runs(listOf("/usr/bin/vim", "-p", "a", "b")))
        assertTrue(policy.runs(listOf("less", "log")))
        assertTrue(policy.runs(listOf("htop")))
        assertTrue(policy.runs(listOf("claude", "--resume", "x")))
        assertTrue(policy.runs(listOf("/Users/me/.local/bin/claude")))
    }

    @Test
    fun onlyTypesWhatDoesSomething() {
        assertFalse(policy.runs(listOf("make", "deploy")))
        assertFalse(policy.runs(listOf("rm", "-r", "build")))
        assertFalse(policy.runs(listOf("npm", "publish")))
        assertFalse(policy.runs(listOf("env", "X=1", "emacs")), "Judged by its name: env runs anything")
        assertFalse(policy.runs(emptyList()))
    }

    @Test
    fun neverRunsWhatAsksForPrivileges() {
        assertFalse(RestorePolicy(extra = setOf("sudo")).runs(listOf("sudo", "emacs", "/etc/hosts")))
        assertFalse(policy.runs(listOf("su", "-")))
    }

    @Test
    fun aReplRunsOnlyBareAndSshOnlyToConnect() {
        assertTrue(policy.runs(listOf("python3")))
        assertFalse(policy.runs(listOf("python3", "script.py")))
        assertTrue(policy.runs(listOf("ssh", "host")))
        assertTrue(policy.runs(listOf("ssh", "-p", "2222", "host")))
        assertFalse(policy.runs(listOf("ssh", "host", "rm -rf /tmp/x")))
    }

    @Test
    fun theUserCanAddPrograms() {
        assertFalse(policy.runs(listOf("make", "watch")))
        assertTrue(RestorePolicy(extra = setOf("make")).runs(listOf("make", "watch")))
        assertEquals(false, RestorePolicy().runs(listOf("foo")))
    }
}
