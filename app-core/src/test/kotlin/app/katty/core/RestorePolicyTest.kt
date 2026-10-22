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
        assertTrue(policy.runs(listOf("/opt/homebrew/bin/opencode")))
        assertTrue(policy.runs(listOf("/opt/homebrew/bin/crush")))
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
    fun sshRunsWhenItOnlyConnects() {
        assertTrue(policy.runs(listOf("ssh", "me@host")))
        assertTrue(policy.runs(listOf("ssh", "-p22", "-A", "host")))
        assertTrue(policy.runs(listOf("ssh", "-At", "-i", "~/.ssh/key", "host")))
        assertTrue(policy.runs(listOf("ssh", "-o", "ServerAliveInterval=30", "host")))
        assertTrue(policy.runs(listOf("ssh", "-J", "bastion", "--", "host")))
        assertTrue(policy.runs(listOf("/usr/bin/ssh", "ssh://host:2222")))
    }

    @Test
    fun sshWithACommandIsOnlyTyped() {
        // What would run on the other machine again, with no space in any word.
        assertFalse(policy.runs(listOf("ssh", "host", "reboot")))
        assertFalse(policy.runs(listOf("ssh", "host", "sudo", "reboot")))
        assertFalse(policy.runs(listOf("ssh", "-t", "host", "./deploy.sh")))
        assertFalse(policy.runs(listOf("ssh", "--", "host", "reboot")))
    }

    @Test
    fun sshOptionsThatRunCommandsAreOnlyTyped() {
        assertFalse(policy.runs(listOf("ssh", "-oProxyCommand=nc %h %p", "host")))
        assertFalse(policy.runs(listOf("ssh", "-o", "proxycommand nc %h %p", "host")))
        assertFalse(policy.runs(listOf("ssh", "-o", "RemoteCommand=reboot", "host")))
        assertFalse(policy.runs(listOf("ssh", "-o", "LocalCommand=touch x", "-o", "PermitLocalCommand=yes", "host")))
        assertFalse(policy.runs(listOf("ssh", "-O", "exit", "host")))
        // An option that is not known cannot be told from one that takes the next word.
        assertFalse(policy.runs(listOf("ssh", "-Z", "host")))
        assertFalse(policy.runs(listOf("ssh", "-p")))
        assertFalse(policy.runs(listOf("ssh")))
    }

    @Test
    fun moshRunsWhenItOnlyConnects() {
        assertTrue(policy.runs(listOf("mosh", "host")))
        assertTrue(policy.runs(listOf("mosh", "-p", "60001", "--predict=always", "-a", "me@host")))
        assertFalse(policy.runs(listOf("mosh", "host", "top")))
        assertFalse(policy.runs(listOf("mosh", "--", "host", "reboot")))
        // Both run a command: on this machine, and on the other.
        assertFalse(policy.runs(listOf("mosh", "--ssh=ssh -i key", "host")))
        assertFalse(policy.runs(listOf("mosh", "--server=mosh-server; reboot", "host")))
        assertFalse(policy.runs(listOf("mosh", "--unknown", "host")))
    }

    @Test
    fun theUserCanAddPrograms() {
        assertFalse(policy.runs(listOf("make", "watch")))
        assertTrue(RestorePolicy(extra = setOf("make")).runs(listOf("make", "watch")))
        assertEquals(false, RestorePolicy().runs(listOf("foo")))
    }
}
