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

// The tests that do not run on Windows, and why: the others run there. Most start their terminals with /bin/sh, or run sh scripts in place of
// the shells and the agents.
val shells = "it starts its terminals with /bin/sh, which TestProgram of terminal's tests would replace"
val shellScripts = "the login shells are sh scripts, and the programs are files without an extension"
val aiScripts = "the AI commands are sh scripts"
val agentScripts = "the agents' commands are sh scripts, and their paths a Unix system's"
val agentFiles = "the files of the agents (Kilo, Cursor, Crush) are read with the paths of a Unix system: resuming an agent comes with the inspection of processes"
val links = "a symbolic link needs a privilege on Windows"
val notOnWindows = mapOf(
    "app.katty.core.AgentDetectionTest.aProfileThatStartsABackgroundProgramDoesNotHoldTheReading" to shellScripts,
    "app.katty.core.AgentDetectionTest.aShellThatFailsOrIsNotThereHasNoPath" to shellScripts,
    "app.katty.core.AgentDetectionTest.aShellThatFailsWhenInteractiveIsReadAsALoginShell" to shellScripts,
    "app.katty.core.AgentDetectionTest.anInteractiveShellThatHangsIsReadAsALoginShell" to shellScripts,
    "app.katty.core.AgentDetectionTest.antigravityIsTheProgramCalledAgy" to shellScripts,
    "app.katty.core.AgentDetectionTest.findsTheAgentsThatAreInstalled" to shellScripts,
    "app.katty.core.AgentDetectionTest.foldersThatDoNotExistAreSkipped" to shellScripts,
    "app.katty.core.AgentDetectionTest.readsThePathOfALoginShell" to shellScripts,
    "app.katty.core.AgentDetectionTest.readsThePathThatOnlyAnInteractiveShellHas" to shellScripts,
    "app.katty.core.AgentDetectionTest.theFolderThatComesFirstWins" to shellScripts,
    "app.katty.core.AgentDetectionTest.theRealLoginShellHasAPath" to shellScripts,
    "app.katty.core.AgentDetectionTest.whatTheProfilePrintsIsNotPartOfThePath" to shellScripts,
    "app.katty.core.AiCommandTest.aCommandThatFailsPrintsNothingOrTakesTooLongIsNoAnswer" to aiScripts,
    "app.katty.core.AiCommandTest.itRunsInTheDirectoryWithThePathItIsGiven" to aiScripts,
    "app.katty.core.AiCommandTest.theAiIsAskedWhyAndItsAnswerRead" to aiScripts,
    "app.katty.core.AiCommandTest.thePromptReachesTheCommandAsItIs" to aiScripts,
    "app.katty.core.AiCommandTest.theAiIsRunWithTheEnvironmentOfTheWorkspaceButItsOwnPath" to aiScripts,
    "app.katty.core.CrushStoreTest.theDatabaseIsWhereTheListOfProjectsSaysItIs" to agentFiles,
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
    "app.katty.core.OmpUsageSourceTest.readsWhatWasSpentTodayOverSevenDaysAndThisMonthAfterItsCommandBroughtTheDatabaseUpToDate" to "the paths of a Unix system",
    "app.katty.core.RunningCommandsTest.anOpenCodeThatThePathOfTheLoginShellResolvesIsKeptByItsName" to links,
    "app.katty.core.StateFileTest.aDamagedFileThatCannotBeSetAsideIsNotWrittenOver" to "POSIX permissions",
    "app.katty.core.TerminalSnapshotStoreTest.aFileThatIsDamagedOrCutIsDeletedAndReadAsNone" to shells,
    "app.katty.core.TerminalSnapshotStoreTest.aSnapshotIsKeptAndComesBackWithItsContent" to shells,
    "app.katty.core.TerminalSnapshotStoreTest.aSnapshotOfAnotherBuildOfLibghosttyIsDeletedAndReadAsNone" to shells,
    "app.katty.core.TerminalSnapshotStoreTest.onlyTheSnapshotsOfTheTerminalsThatAreKeptStay" to shells,
    "app.katty.core.TerminalSnapshotStoreTest.savingAgainReplacesTheSnapshotAndLeavesNoTemporaryFile" to shells,
    "app.katty.core.TerminalSnapshotStoreTest.theFilesAreForTheUserOnly" to shells,
    "app.katty.core.TerminalSnapshotStoreTest.theIdOfATerminalIsNeverAPath" to shells,
    "app.katty.core.UsageServiceTest.antigravityIsOnlyAskedWhenItsVersionCanAnswerForFree" to agentScripts,
    "app.katty.core.UsageServiceTest.claudeIsReadFromItsOwnCommand" to agentScripts,
    "app.katty.core.UsageServiceTest.codexIsAskedOverJsonRpc" to agentScripts,
    "app.katty.core.UsageServiceTest.theCommandsRunInTheDirectoryOfTheApplicationAndNotInAProject" to agentScripts,
    "app.katty.core.UsageServiceTest.theProcessRunnerGivesWhatAProgramPrintedAndItsCode" to agentScripts,
    "app.katty.core.UsageServiceTest.theProgramsThatAProgramThatTakesTooLongStartedAreEndedWithIt" to agentScripts,
    "app.katty.core.UsageServiceTest.whatAProgramPrintedIsReadEvenWhenAProgramItStartedKeepsTheOutputOpen" to agentScripts,
)

tasks.test {
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

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    testRuntimeOnly(libs.slf4j.simple)
}
