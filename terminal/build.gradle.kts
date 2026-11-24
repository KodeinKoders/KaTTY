plugins {
    alias(libs.plugins.kotlin.jvm)
    // TestProgram and sh(), which the tests of the other modules start their terminals with.
    `java-test-fixtures`
}

kotlin {
    // The JetBrains Runtime, which the application ships with: what is tested is what runs.
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(findProperty("jvm.toolchain")!!.toString().toInt())
        vendor = JvmVendorSpec.JETBRAINS
    }
    explicitApi()
}

val typedAtZsh = "zsh, which a Unix system has: PowerShellIntegrationTest types a command at the prompt of PowerShell"

// The tests that do not run on Windows, and why. The others run there in ConPTY, with TestProgram instead of /bin/sh.
val notOnWindows = mapOf(
    "app.katty.terminal.ForegroundCommandTest" to "what runs in a terminal of a Unix system (its group, its tty): WindowsProcessesTest has Windows's",
    "app.katty.terminal.ForegroundProcessTest" to "what runs in a terminal of a Unix system (its group): WindowsProcessesTest has Windows's",
    "app.katty.terminal.OpenFilesTest" to "the open files of a process are not read on Windows",
    "app.katty.terminal.SessionProcessesTest" to "the session of a Unix system (getsid, the hang-up): WindowsProcessesTest has Windows's",
    "app.katty.terminal.ShellIntegrationTest" to "the zsh and bash of a Unix system: MsysShellIntegrationTest has the ones of Windows",
    "app.katty.terminal.TypedCommandTest.runsTheCommandAtTheFirstPromptAsIfTheUserTypedIt" to typedAtZsh,
    "app.katty.terminal.TypedCommandTest.leavesTheCommandOnTheLineWhenItIsNotToRun" to typedAtZsh,
    "app.katty.terminal.TypedCommandTest.typesItAfterASecondWhenThereAreNoPromptMarks" to typedAtZsh,
    "app.katty.terminal.TypedCommandTest.typesItWhenThePromptMarksThatWereExpectedNeverCome" to typedAtZsh,
    "app.katty.terminal.TypedCommandTest.aWordThatStartsWithAnEqualsSignIsTypedAsItIs" to typedAtZsh,
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
    // KATTY_TEST_PROGRAM=1 runs the scripts of the tests with TestProgram on a Unix system too (TestShell.kt): the tests run again when it changes.
    inputs.property("testProgram", providers.environmentVariable("KATTY_TEST_PROGRAM").orElse(""))
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
