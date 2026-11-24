plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.serialization)
}

kotlin {
    // The JetBrains Runtime, which the application ships with: what is tested is what runs.
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(findProperty("jvm.toolchain")!!.toString().toInt())
        vendor = JvmVendorSpec.JETBRAINS
    }
    explicitApi()
}

// The tests that do not run on Windows, and why: the others run there, their scripts with TestProgram of terminal's test fixtures.
val shellScripts = "it reads the PATH of a login shell, which Windows has none of: the application takes its own there"
val agentFiles = "where Kilo and Cursor keep their files on Windows was not seen, and Kilo's session is found by the open files of its process, which are not read there"
val links = "a symbolic link needs a privilege on Windows"
val notOnWindows = mapOf(
    "app.katty.core.AgentDetectionTest.aProfileThatStartsABackgroundProgramDoesNotHoldTheReading" to shellScripts,
    "app.katty.core.AgentDetectionTest.aShellThatFailsOrIsNotThereHasNoPath" to shellScripts,
    "app.katty.core.AgentDetectionTest.aShellThatFailsWhenInteractiveIsReadAsALoginShell" to shellScripts,
    "app.katty.core.AgentDetectionTest.anInteractiveShellThatHangsIsReadAsALoginShell" to shellScripts,
    "app.katty.core.AgentDetectionTest.readsThePathOfALoginShell" to shellScripts,
    "app.katty.core.AgentDetectionTest.readsThePathThatOnlyAnInteractiveShellHas" to shellScripts,
    "app.katty.core.AgentDetectionTest.theRealLoginShellHasAPath" to shellScripts,
    "app.katty.core.AgentDetectionTest.whatTheProfilePrintsIsNotPartOfThePath" to shellScripts,
    "app.katty.core.CursorStoreTest.aContinueWithNoIdIsTheNewestChatOfTheDirectoryWithAConversationThatWasWrittenToSince" to agentFiles,
    "app.katty.core.CursorStoreTest.aNewChatMadeAfterAnotherIsTheOneItIsInAtOnce" to agentFiles,
    "app.katty.core.CursorStoreTest.aNewCursorWithSomethingSaidIsInTheChatItMadeWhenItStarted" to agentFiles,
    "app.katty.core.CursorStoreTest.aResumedChatWithAConversationKeepsItsTimeSoItIsTheOneOfTheCommandLine" to agentFiles,
    "app.katty.core.CursorStoreTest.aSubAgentsChatIsNotTheOnesOfTheTerminalAndAnotherDirectoryHasItsOwnFolder" to agentFiles,
    "app.katty.core.GitStatusTest.theShimOfMacOSIsOnlyTakenWithTheDeveloperTools" to "the shim of git of macOS",
    "app.katty.core.KiloStoreTest.aLogOfAnotherFolderOrASessionTheDatabaseDoesNotKnowIsNotTaken" to agentFiles,
    "app.katty.core.KiloStoreTest.aLogThatNamesNothingLeavesTheNewestSessionOfTheDirectorySinceTheProcessStarted" to agentFiles,
    "app.katty.core.KiloStoreTest.theDatabaseIsTheOneThatTheProcessHasOpenWhateverItsChannel" to agentFiles,
    "app.katty.core.KiloStoreTest.theLastSessionTheLogNamesIsTheOneItIsInAndASubAgentsIsItsRoot" to agentFiles,
    "app.katty.core.KiloStoreTest.theSessionIsStillKnownWhenTheLogHasGrownLongPastItsLine" to agentFiles,
    "app.katty.core.KiloStoreTest.theSessionIsTheOneThatTheLogOfTheProcessNames" to agentFiles,
    "app.katty.core.KiloStoreTest.twoKilosOfOneDirectoryAreToldApartByTheirLogs" to agentFiles,
    "app.katty.core.OmpStoreTest.theDirectoryIsComparedAsARealPath" to links,
    "app.katty.core.RunningCommandsTest.anOpenCodeThatThePathOfTheLoginShellResolvesIsKeptByItsName" to links,
    "app.katty.core.StateFileTest.aDamagedFileThatCannotBeSetAsideIsNotWrittenOver" to "POSIX permissions",
    "app.katty.core.TerminalSnapshotStoreTest.theFilesAreForTheUserOnly" to "POSIX permissions",
)

tasks.test {
    // KATTY_TEST_PROGRAM=1 runs the scripts of the tests with TestProgram on a Unix system too (TestShell.kt): the tests run again when it changes.
    inputs.property("testProgram", providers.environmentVariable("KATTY_TEST_PROGRAM").orElse(""))
    // The sessions that the tests start call the FFM API and pty4j's JNA, which are restricted.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    if (System.getProperty("os.name").startsWith("Windows")) {
        filter { notOnWindows.keys.forEach(::excludeTestsMatching) }
    }
}

dependencies {
    // TerminalSession and what it publishes are part of the API.
    api(projects.terminal)

    // StateFlow is part of KattyStore's API.
    api(libs.kotlinx.coroutines)

    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    // sh(), which starts a script with TestProgram on Windows.
    testImplementation(testFixtures(projects.terminal))
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    testRuntimeOnly(libs.slf4j.simple)
}
