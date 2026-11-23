plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    // The JetBrains Runtime, which the application ships with: what is tested is what runs.
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(findProperty("jvm.toolchain")!!.toString().toInt())
        vendor = JvmVendorSpec.JETBRAINS
    }
    explicitApi()
}

// The tests that do not run on Windows, and why. The others run there in ConPTY, with TestProgram instead of /bin/sh.
val notOnWindows = mapOf(
    "app.katty.terminal.ForegroundCommandTest" to "what runs in a terminal is not known on Windows yet",
    "app.katty.terminal.ForegroundProcessTest" to "what runs in a terminal is not known on Windows yet",
    "app.katty.terminal.OpenFilesTest" to "the open files of a process are not known on Windows yet",
    "app.katty.terminal.SessionProcessesTest" to "the processes of a session are not known on Windows yet",
    "app.katty.terminal.ShellIntegrationTest" to "zsh and bash",
    "app.katty.terminal.TypedCommandTest" to "zsh",
    "app.katty.terminal.TerminalAnswersTest.theCursorIsAtAPasswordPromptWhileTheProgramReadsALineWithTheEchoOff" to "a console has no termios",
    "app.katty.terminal.TerminalSessionTest.showsARedrawThatTakesSeveralWritesWhole" to "perl",
    "app.katty.terminal.TerminalSessionTest.stillPublishesWhileTheProgramKeepsWriting" to "perl",
    "app.katty.terminal.TerminalSessionTest.tellsTheProgramTheSizeOfItsCellsInPixelsInTheSizeOfThePty" to "a console has no size in pixels",
    "app.katty.terminal.TerminalSessionTest.closingHangsUpAShellWhichSavesItsHistoryAndEndsAtOnce" to "bash and SIGHUP",
    "app.katty.terminal.TerminalSessionTest.closingReleasesThePtyAndWhatAsksForItMeanwhileGetsNothing" to "/dev/fd",
    "app.katty.terminal.TerminalSessionTest.aProgramGetsAUtf8LocaleWhenTheApplicationHasNone" to "Windows has no LANG",
    "app.katty.terminal.TerminalSessionTest.runsTheProgramInATerminalThatSupportsColors" to "tput and terminfo",
    "app.katty.terminal.CommandMarksTest.aCommandThatTheShellStoppedIsStoppedAndNotFailed" to "Windows stops no job",
    // Measured: the answer of libghostty never reaches the program, which ConPTY gives none of the OSC answers of the terminal.
    "app.katty.terminal.ProgramStatusTest.theTerminalAnswersTheQueryOfTheSupportOfTheProtocol" to "ConPTY does not pass OSC answers on",
)

tasks.test {
    // ghostty-vt calls the FFM API, and pty4j calls JNA: both are restricted, and the JVM warns on the
    // first native call without this flag.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    if (System.getProperty("os.name").startsWith("Windows")) {
        filter { notOnWindows.keys.forEach(::excludeTestsMatching) }
    }
}

dependencies {
    // StateFlow is part of TerminalSession's API.
    api(libs.kotlinx.coroutines)

    implementation(projects.ghosttyVt)

    implementation(libs.pty4j)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlin.test.junit)

    testRuntimeOnly(libs.slf4j.simple)
}
