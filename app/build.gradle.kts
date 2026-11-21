import org.gradle.jvm.toolchain.JvmVendorSpec
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.buildconfig)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.compose.multiplatform)
}

buildConfig {
    packageName("app.katty")
    // The version of the project, declared once in the root build script: the About panel says it, and it is the bundle's `packageVersion`.
    buildConfigField("VERSION", project.version.toString())
    // The short hash of the commit it is built from, which tells two builds of the same version apart; "unknown" outside a git checkout.
    val commit = providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }.orElse("unknown")
    buildConfigField("GIT_COMMIT", commit)
    // The branch it is built on, which the About panel says when it is not main; empty for a detached HEAD and outside a git checkout.
    val branch = providers.exec {
        commandLine("git", "branch", "--show-current")
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim() }.orElse("")
    buildConfigField("GIT_BRANCH", branch)
    // When the commit it is built from was made, which the About panel says: the committer's date, with its offset (`2026-11-03T19:43:42+01:00`),
    // the same for every build of the commit; empty outside a git checkout.
    val commitDate = providers.exec {
        commandLine("git", "log", "-1", "--format=%cI", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim() }.orElse("")
    buildConfigField("GIT_COMMIT_DATE", commitDate)
}

val jvmToolchainVersion = findProperty("jvm.toolchain")!!.toString().toInt()

kotlin {
    // Jewel's decorated window draws the title bar of the window and needs the JetBrains Runtime for it (it is what gives the native
    // buttons of macOS a place in our title bar). It has the same Java as the other modules, so FFM is final.
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(jvmToolchainVersion)
        vendor = JvmVendorSpec.JETBRAINS
    }
}

// Jewel's icons depend on IntelliJ's fork of kotlinx-coroutines, which has the same classes under another group: Gradle kept both jars, and which
// one is loaded depends on their order. The packaged application has them in alphabetical order, the fork first, and the code compiled against
// kotlinx-coroutines failed in it (the fork's Job has no `cancel$default`). The icons only use the API of kotlinx-coroutines itself (checked on
// Jewel 0.41 and 0.42), which replaces the fork.
val coroutinesFork = libs.kotlinx.coroutines.intellij.get().module.toString()
val coroutines = libs.kotlinx.coroutines.asProvider().get().let { "${it.module}:${it.versionConstraint.requiredVersion}" }
configurations.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module(coroutinesFork))
            .using(module(coroutines))
            .because("IntelliJ's fork has the classes of kotlinx-coroutines, and code compiled against kotlinx-coroutines fails with it")
    }
}

dependencies {
    implementation(projects.appCore)
    implementation(projects.terminal)
    implementation(projects.terminalCompose)
    implementation(projects.terminalThemes)

    // Jewel has its own components: the Material ones are not wanted.
    implementation(compose.desktop.currentOs) { exclude(group = "org.jetbrains.compose.material") }

    implementation(libs.compose.foundation)
    implementation(libs.compose.runtime)

    implementation(libs.jewel.decorated.window)
    implementation(libs.jewel.int.ui)

    implementation(libs.kotlinx.coroutines.swing)

    implementation(libs.slf4j.api)

    // Prints the logs of the libraries that log with SLF4J.
    runtimeOnly(libs.slf4j.simple)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.compose.ui.test.junit4)
}

// The icon of the application is the logo of the repository on a rounded square of this colour, the shape that macOS wants: since macOS 26 it puts
// the icon of another shape on a grey one. The colour is the one that the logo was cut out of.
val iconBackground = "#FDDDD6"
val appIconFile = layout.buildDirectory.file("icons/icon.png")
val appIcon = tasks.register<Exec>("appIcon") {
    group = "build"
    description = "Draws the icon of the application (icon.png): the logo on a rounded square."
    val logo = rootProject.file("logo.png")
    inputs.file(logo)
    inputs.file("make-icon.java")
    inputs.property("background", iconBackground)
    outputs.file(appIconFile)
    // In a Java process of its own, without a screen: AWT in the process of Gradle would give it an icon in the Dock.
    val java = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(jvmToolchainVersion)
        vendor = JvmVendorSpec.JETBRAINS
    }.get().executablePath.asFile.absolutePath
    commandLine(java, "-Djava.awt.headless=true", "make-icon.java", logo.absolutePath, iconBackground, appIconFile.get().asFile.absolutePath)
}

// The logo is the icon of the windows, and the icon of the application the one of the Dock while the application runs: they are resources of it.
tasks.processResources {
    from(rootProject.file("logo.png")) { into("app/katty") }
    from(appIcon) { into("app/katty") }
}

// The icon of the application that is packaged (the bundle and its disk image) is made from the same icon, with the tools of macOS: the only
// platform that the application is packaged for.
val macIcon = tasks.register<Exec>("macIcon") {
    group = "build"
    description = "Makes the macOS icon (KaTTY.icns) of the packaged application from the icon of the application."
    val icns = layout.buildDirectory.file("icons/KaTTY.icns")
    inputs.files(appIcon)
    inputs.file("make-icns.sh")
    outputs.file(icns)
    commandLine("sh", "make-icns.sh", appIconFile.get().asFile.absolutePath, icns.get().asFile.absolutePath)
}

tasks.withType<AbstractJPackageTask>().configureEach {
    dependsOn(macIcon)
}

// What macOS shows when a program that runs in a terminal asks for what it protects: it asks on behalf of KaTTY, and refuses without asking when
// KaTTY has no description for it (and no entitlement, for the ones that have one: entitlements.plist). The same as Ghostty's.
val usageDescriptions = mapOf(
    "NSAppleEventsUsageDescription" to "use AppleScript",
    "NSAudioCaptureUsageDescription" to "access your system's audio",
    "NSBluetoothAlwaysUsageDescription" to "use Bluetooth",
    "NSCalendarsUsageDescription" to "access your Calendar",
    "NSCameraUsageDescription" to "use the camera",
    "NSContactsUsageDescription" to "access your Contacts",
    "NSLocalNetworkUsageDescription" to "access the local network",
    "NSLocationUsageDescription" to "access your location information",
    "NSMicrophoneUsageDescription" to "use your microphone",
    "NSMotionUsageDescription" to "access motion data",
    "NSPhotoLibraryUsageDescription" to "access your Photo Library",
    "NSRemindersUsageDescription" to "access your reminders",
    "NSSpeechRecognitionUsageDescription" to "use speech recognition",
    "NSSystemAdministrationUsageDescription" to "use elevated privileges",
)

// The tests that do not run on Windows, and why: the others run there. Most start their terminals with /bin/sh, or run sh scripts in place of
// the shells and the agents.
val shells = "it starts its terminals with /bin/sh, which TestProgram of terminal's tests would replace"
val notOnWindows = mapOf(
    "app.katty.terminals.AgentTrackerTest.aChangeOfStateIsATransitionWithTheStateItLeft" to shells,
    "app.katty.terminals.AgentTrackerTest.aCommandThatEndsIsToldWithItsLineHowItWentAndHowLongItRan" to shells,
    "app.katty.terminals.AgentTrackerTest.aCommandThatIsKeptStaysUntilTheTerminalIsSeen" to shells,
    "app.katty.terminals.AgentTrackerTest.aProgramThatIsNotAKnownAgentStaysAtRestAfterItsDoneWasSeen" to shells,
    "app.katty.terminals.AgentTrackerTest.aProgramThatNamesNothingStaysAtRestAfterItsDoneWasSeen" to shells,
    "app.katty.terminals.AgentTrackerTest.aShellThatWasStartedAgainStartsAtRest" to shells,
    "app.katty.terminals.AgentTrackerTest.aTerminalThatIsGoneIsNotFollowedAnyMore" to shells,
    "app.katty.terminals.AgentTrackerTest.aTerminalThatSaysNothingIsAtRest" to shells,
    "app.katty.terminals.AgentTrackerTest.aTerminalThatWaitsForTheUserNeedsInput" to shells,
    "app.katty.terminals.AgentTrackerTest.anAgentThatEndedAtRestLeavesNoCard" to shells,
    "app.katty.terminals.AgentTrackerTest.anAgentThatEndedBeforeItsFinishedStateWasSeenLeavesNoGhostOfItInTheShell" to shells,
    "app.katty.terminals.AgentTrackerTest.anAgentThatWasSeenReportsAgain" to shells,
    "app.katty.terminals.AgentTrackerTest.followsWhatTheProgramReports" to shells,
    "app.katty.terminals.AgentTrackerTest.markingAnAgentSeenTakesWhatItFinishedOff" to shells,
    "app.katty.terminals.AgentTrackerTest.markingEverythingSeenDoesItForEveryTerminal" to shells,
    "app.katty.terminals.AgentTrackerTest.markingSeenLeavesWhatIsStillWorking" to shells,
    "app.katty.terminals.AgentTrackerTest.reportsThatComeAtTheSameMomentFromManyTerminalsAreAllKept" to shells,
    "app.katty.terminals.AgentTrackerTest.theAgentIsForgottenWhenTheShellHasTheTerminalAgain" to shells,
    "app.katty.terminals.AgentTrackerTest.theIdleRecordOfAnAgentThatEndedIsNotTheNextProgramsCard" to shells,
    "app.katty.terminals.AgentTrackerTest.theLineIsThereWhenTheEndIsToldInTheWriteThatStartedTheCommand" to shells,
    "app.katty.terminals.AgentTrackerTest.theTimeIsThatOfTheColumnSoAWaitThatBecomesAFailureKeepsIt" to shells,
    "app.katty.terminals.AgentTrackerTest.theTimeIsWhenTheStateChangedNotWhenItWasSaidAgain" to shells,
    "app.katty.terminals.AllTerminalsSearchTest.aLongHistoryIsReadInPiecesWithTheRightRows" to shells,
    "app.katty.terminals.AllTerminalsSearchTest.aTerminalGivesSoManyRowsAndAllOfThemSoManyMore" to shells,
    "app.katty.terminals.AllTerminalsSearchTest.aTerminalThatWroteSinceIsReadAgain" to shells,
    "app.katty.terminals.AllTerminalsSearchTest.anEmptyQueryFindsNothing" to shells,
    "app.katty.terminals.AllTerminalsSearchTest.theRowsOfEachTerminalAreFoundTheLastFirstInTheOrderOfTheTerminals" to shells,
    "app.katty.terminals.AllTerminalsSearchTest.whatIsFoundIsGivenAfterEachTerminal" to shells,
    "app.katty.screens.FindBarTest.aBarThatOpensOnATextSearchesItAtOnce" to shells,
    "app.katty.screens.FindBarTest.closingTakesTheMatchesOffTheScreen" to shells,
    "app.katty.screens.FindBarTest.searchesWhatIsTypedAndMovesFromMatchToMatch" to shells,
    "app.katty.KattyWindowContentTest.aChangeOfTheCursorSettingsReachesTheRunningTerminals" to shells,
    "app.katty.KattyWindowContentTest.aClickInTheTerminalWhereACommandEndedIsHavingSeenIt" to shells,
    "app.katty.KattyWindowContentTest.aCommandIsNotTypedWhileAProgramRuns" to shells,
    "app.katty.KattyWindowContentTest.aCommandIsRunOverThePanesMinimizedShownAgainAndStopped" to shells,
    "app.katty.KattyWindowContentTest.aCommandThatEndsInATerminalThatIsNotSelectedIsInTheBellUntilItIsSeen" to shells,
    "app.katty.KattyWindowContentTest.aNoticeIsNotShownDuringAPresentation" to shells,
    "app.katty.KattyWindowContentTest.aNoticeIsShownInTeachMode" to shells,
    "app.katty.KattyWindowContentTest.aTerminalThatFinishedInFrontOfTheUserStaysInTheBellUntilTheyTypeInIt" to shells,
    "app.katty.KattyWindowContentTest.anAgentThatFailsWhereTheUserIsNotLookingMakesASound" to shells,
    "app.katty.KattyWindowContentTest.anAgentThatFinishesMakesNoSound" to shells,
    "app.katty.KattyWindowContentTest.anAgentThatNeedsTheUserMakesNoSoundDuringAPresentation" to shells,
    "app.katty.KattyWindowContentTest.anAgentThatNeedsTheUserMakesNoSoundWhenTheSettingIsOff" to shells,
    "app.katty.KattyWindowContentTest.anAgentThatNeedsTheUserWhereTheyAreNotLookingMakesASound" to shells,
    "app.katty.KattyWindowContentTest.closingATerminalWhereAProgramRunsAsksFirst" to shells,
    "app.katty.KattyWindowContentTest.commandEnterRunsTheCommandMinimized" to shells,
    "app.katty.KattyWindowContentTest.noCommandIsAskedForWhileTheAiIsOff" to shells,
    "app.katty.KattyWindowContentTest.nothingAsksTheAiWhileItIsOff" to shells,
    "app.katty.KattyWindowContentTest.presentationModeShowsOnlyTheTerminalsAndTheKeysThatArePressed" to shells,
    "app.katty.KattyWindowContentTest.quittingWhileAProgramRunsAsksAndCancelKeepsTheApplication" to shells,
    "app.katty.KattyWindowContentTest.teachModeShowsTheKeysThatArePressedAndChangesNothingElse" to shells,
    "app.katty.KattyWindowContentTest.theAiIsAskedForACommandWhichIsTypedAtThePromptAndNotRun" to shells,
    "app.katty.KattyWindowContentTest.theAiIsAskedForTheCommandWhileTheFieldIsEmpty" to shells,
    "app.katty.KattyWindowContentTest.theAiIsAskedToExplainTheResultOfTheLastCommandThatSucceeded" to shells,
    "app.katty.KattyWindowContentTest.theAiIsAskedWhyTheLastCommandFailedAndItsCorrectionIsTyped" to shells,
    "app.katty.KattyWindowContentTest.theAiIsAskedWhyTheRunFailedAndItsCorrectionGoesInTheQuestion" to shells,
    "app.katty.KattyWindowContentTest.theExportSaysWhetherAProgramHasTheAlternateScreen" to shells,
    "app.katty.KattyWindowContentTest.thePaletteOpensAndClosesWithItsShortcutAndADialogKeepsTheOthers" to shells,
    "app.katty.KattyWindowContentTest.theQuestionHasNoAskAiWhileTheAiIsOff" to shells,
    "app.katty.KattyWindowContentTest.theRunIsRestartedFromItsHeader" to shells,
    "app.katty.KattyWindowContentTest.theRunOverThePanesHasTheKeyboard" to shells,
    "app.katty.terminals.ReportedSessionTest" to shells,
    "app.katty.RestoreTest" to shells,
    "app.katty.terminals.RunTerminalsTest.aCommandThatEndsByItselfStaysWithItsExitCode" to shells,
    "app.katty.terminals.RunTerminalsTest.aNewRunStartsOnceWhatTheOldOneStartedHasEnded" to shells,
    "app.katty.terminals.RunTerminalsTest.aRunThatIsStoppedIsGoneWithItsProgram" to shells,
    "app.katty.terminals.RunTerminalsTest.aWorkspaceHasOneRunAndANewOneStopsTheOld" to shells,
    "app.katty.terminals.RunTerminalsTest.onlyTheRunOfTheWorkspaceOnTheScreenWhileItIsShownDrawsFrames" to shells,
    "app.katty.terminals.RunTerminalsTest.theRegistryRunsTheCommandWithTheUsersShellAndStopsTheRunsOfClosedWorkspaces" to shells,
    "app.katty.terminals.RunTerminalsTest.theRunOfAWorkspaceThatIsGoneIsStopped" to shells,
    "app.katty.terminals.RunningRestoreTest" to "zsh",
    "app.katty.terminals.SessionRegistrySnapshotsTest" to shells,
    "app.katty.terminals.SessionRegistryTest.aBranchThatIsCheckedOutWhileAProgramRunsIsSeen" to shells,
    "app.katty.terminals.SessionRegistryTest.aChangeOfTheCursorIsGivenToTheRunningSessions" to shells,
    "app.katty.terminals.SessionRegistryTest.aChangeOfTheDefaultSchemeReachesTheTerminalsThatDoNotHaveTheirOwn" to shells,
    "app.katty.terminals.SessionRegistryTest.aChangeOfTheTextSettingsIsGivenToTheRunningSessions" to shells,
    "app.katty.terminals.SessionRegistryTest.aShellThatEndsLeavesItsOutputAndSaysThatItIsOver" to shells,
    "app.katty.terminals.SessionRegistryTest.aShellThatIsStartedAgainHasTheSettingsAsTheyAreNow" to shells,
    "app.katty.terminals.SessionRegistryTest.aShellThatTakesLongToStartDoesNotHoldTheCaller" to shells,
    "app.katty.terminals.SessionRegistryTest.aTerminalKnowsTheBranchItsShellIsIn" to shells,
    "app.katty.terminals.SessionRegistryTest.aTerminalOutsideARepositoryHasNoBranchAndOneThatGoesThereLosesIt" to shells,
    "app.katty.terminals.SessionRegistryTest.aTerminalThatIsGoneBeforeItsShellStartedHasThatShellClosed" to shells,
    "app.katty.terminals.SessionRegistryTest.aTerminalThatIsOpenedAgainBeforeItsFirstShellStartedHasTheShellThatWasStartedForIt" to shells,
    "app.katty.terminals.SessionRegistryTest.aTerminalWithoutASchemeOfItsOwnFollowsTheThemeOfTheApplication" to shells,
    "app.katty.terminals.SessionRegistryTest.closesTheSessionOfATerminalThatIsGone" to shells,
    "app.katty.terminals.SessionRegistryTest.hidesTheSessionsThatAreNotShown" to shells,
    "app.katty.terminals.SessionRegistryTest.loweringTheScrollbackDeletesTheOlderLinesOfTheRunningSessions" to shells,
    "app.katty.terminals.SessionRegistryTest.reopeningAShellForgetsWhatRanOverTheOldOneForGood" to shells,
    "app.katty.terminals.SessionRegistryTest.restartingStartsAnotherShellInThePlaceOfTheFirst" to shells,
    "app.katty.terminals.SessionRegistryTest.startsOneSessionForEachTerminalOfEveryWorkspace" to shells,
    "app.katty.terminals.SessionRegistryTest.syncingAgainKeepsTheSessionsThatAreThere" to shells,
    "app.katty.terminals.SessionRegistryTest.theBranchIsTheOneOfTheDirectoryOfTheProgramThatRunsThenTheShells" to shells,
    "app.katty.terminals.SessionRegistryTest.theChangesOfTheTreeAreSeenWithoutTheShellSayingAnything" to shells,
    "app.katty.terminals.SessionRegistryTest.theColorSchemeOfATerminalIsItsOwnOrTheDefaultOfTheSettings" to shells,
    "app.katty.terminals.SessionRegistryTest.theProgramThatHasEachTerminalIsToldInOnePlaceAndForgottenWithTheTerminal" to shells,
    "app.katty.terminals.SessionRegistryTest.whatRanOverAShellThatEndedIsNotStartedAgain" to shells,
    "app.katty.terminals.SessionRegistryTest.whereTheShellGoesIsWhereTheTerminalStartsNextTime" to shells,
    "app.katty.WindowControllerRunTest.aCommandThatEndedRunsAgainAtOnce" to shells,
    "app.katty.WindowControllerRunTest.aRunWhileACommandRunsWarnsThenAsksBeforeItStopsIt" to shells,
    "app.katty.WindowControllerRunTest.aRunWhoseCommandEndedByItselfIsReplacedWithoutAQuestion" to shells,
    "app.katty.WindowControllerRunTest.closingAWorkspaceWhoseCommandRunsAsksAndOneThatEndedDoesNot" to shells,
    "app.katty.WindowControllerRunTest.commandShiftLRunsTheCommandAgain" to shells,
    "app.katty.WindowControllerRunTest.commandWStopsTheRunThatIsShownAndClosesThePaneWhenItIsMinimized" to shells,
    "app.katty.WindowControllerRunTest.quittingAsksWhileACommandRuns" to shells,
    "app.katty.WindowControllerRunTest.stoppingACommandThatRunsCanBeCancelledAndOneThatEndedClosesAtOnce" to shells,
    "app.katty.WindowControllerRunTest.thePaletteHasTheRunAndWhatActsOnIt" to shells,
    "app.katty.WindowControllerRunTest.whileTheRunIsShownWhatActsOnATerminalActsOnItAndWhatActsOnThePanesDoesNothing" to shells,
    "app.katty.WindowControllerTest.aFileThatCannotBeWrittenIsTold" to shells,
    "app.katty.WindowControllerTest.aSavePanelThatIsCancelledWritesNothing" to shells,
    "app.katty.WindowControllerTest.theHistoryIsExportedInTheFormatThatIsChosenWhereTheUserSays" to shells,
    "app.katty.WindowControllerTest.thereIsNothingToExportOnTheBoardNorForATerminalWithoutASession" to shells,
    "app.katty.screens.WorkspaceAreaTest.aNameThatTheUserGaveStaysWhateverRuns" to shells,
    "app.katty.screens.WorkspaceAreaTest.aPaneGrowsToItsMaximizedPlaceAndGoesBackToItsOwn" to shells,
    "app.katty.screens.WorkspaceAreaTest.aProgramThatReportsItsStateHasTheOrdinaryMarginAndNoGutter" to shells,
    "app.katty.screens.WorkspaceAreaTest.aRightClickInTheTerminalOffersItsTextAndEveryItemOfTheMenuOfThePane" to shells,
    "app.katty.screens.WorkspaceAreaTest.aRightClickOffersToAskTheAiAboutTheLastCommand" to shells,
    "app.katty.screens.WorkspaceAreaTest.aTerminalIsCalledAfterTheProgramThatRunsInItUntilItEnds" to shells,
    "app.katty.screens.WorkspaceAreaTest.aTerminalIsNotAgainstTheBorderOfItsPane" to shells,
    "app.katty.screens.WorkspaceAreaTest.everyTerminalOfTheWorkspaceHasItsShell" to shells,
    "app.katty.screens.WorkspaceAreaTest.theFocusedTerminalTakesTheKeyboardBackWhenTheFocusTickChanges" to shells,
    "app.katty.screens.WorkspaceAreaTest.theGutterOfTheCommandMarksIsAtTheLeftOfThePaddingAndPushesTheGridByFiveDp" to shells,
    "app.katty.screens.WorkspaceAreaTest.theMenuOfTheBarOfACommandHidesItsTooltipAndSelectsItsOutput" to shells,
    "app.katty.screens.WorkspaceAreaTest.theTooltipOfTheBarGoesWhileSomethingIsOpenOverTheTerminals" to shells,
)

tasks.test {
    // The FFM API is restricted: without this flag, the JVM warns on the first native call.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    if (System.getProperty("os.name").startsWith("Windows")) {
        filter { notOnWindows.keys.forEach(::excludeTestsMatching) }
    }
}

compose {
    desktop.application {
        mainClass = "app.katty.MainKt"

        // The run task starts the JVM that runs Gradle unless it is told which one: it has to be the toolchain's, which is the JetBrains Runtime.
        javaHome = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(jvmToolchainVersion)
            vendor = JvmVendorSpec.JETBRAINS
        }.get().metadata.installationPath.asFile.absolutePath

        // ghostty-vt calls the FFM API, and pty4j calls JNA: both are restricted, and the JVM warns on the
        // first native call without this flag.
        jvmArgs += "--enable-native-access=ALL-UNNAMED"

        nativeDistributions {
            // The disk image is for macOS on Apple Silicon, the only platform libghostty-vt is built for.
            targetFormats(TargetFormat.Dmg)
            packageName = "KaTTY"
            // The project's: macOS does not accept a version whose first number is 0.
            packageVersion = project.version.toString()
            description = "A terminal manager for coding agents"
            vendor = "Kodein Koders"
            // The FFM calls of ghostty-vt and the JNA calls of pty4j are restricted.
            jvmArgs += "--enable-native-access=ALL-UNNAMED"
            // Added to the modules that Compose puts in the runtime of the bundle (java.desktop and what it needs): the git status of the
            // terminals watches their working trees with all their folders, `ExtendedWatchEventModifier.FILE_TREE`, which is in jdk.unsupported.
            modules("jdk.unsupported")

            macOS {
                bundleID = "app.katty"
                dockName = "KaTTY"
                iconFile.set(layout.buildDirectory.file("icons/KaTTY.icns"))
                // The ones of the application, which runs the programs of the terminals. The runtime keeps Compose's, which are the JVM's.
                entitlementsFile.set(file("entitlements.plist"))
                infoPlist {
                    extraKeysRawXml = usageDescriptions.entries.joinToString("\n") { (key, what) ->
                        "<key>$key</key>\n<string>A program running in KaTTY would like to $what.</string>"
                    }
                }
            }
        }
    }
}
