package app.katty.terminal

import java.io.File
import java.util.Base64

/** Whether the tests run on Windows, which has no `/bin/sh`. */
public val testsOnWindows: Boolean = System.getProperty("os.name").startsWith("Windows")

/**
 * The command that runs [script] in a terminal: `/bin/sh` on a Unix system, and on Windows [TestProgram], which runs the part of `sh` that the
 * tests use, the same way (`TestProgramTest` compares them).
 */
public fun sh(script: String): List<String> = if (usesTestProgram) testProgram(script) else listOf("/bin/sh", "-c", script)

/** Whether [sh] runs [TestProgram]: on Windows, and elsewhere with `KATTY_TEST_PROGRAM=1`, which tells whether a script is in what it knows without a Windows. */
private val usesTestProgram: Boolean = testsOnWindows || System.getenv("KATTY_TEST_PROGRAM") == "1"

/** The command that runs [script] with [TestProgram]: the JVM of the tests, with the folder of its class only. */
public fun testProgram(script: String): List<String> {
    val java = ProcessHandle.current().info().command().orElseThrow()
    val classes = File(TestProgram::class.java.protectionDomain.codeSource.location.toURI()).path
    // It starts quickly: the tests wait for what it writes, and it lives a few seconds at most.
    return listOf(
        java, "--enable-native-access=ALL-UNNAMED", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-Xshare:auto", "-cp", classes,
        TestProgram::class.java.name, Base64.getEncoder().encodeToString(script.toByteArray()),
    )
}
