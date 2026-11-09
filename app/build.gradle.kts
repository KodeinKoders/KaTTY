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
// Jewel 0.41), which replaces the fork.
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

tasks.test {
    // The FFM API is restricted: without this flag, the JVM warns on the first native call.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
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
