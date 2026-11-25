import org.gradle.jvm.toolchain.JvmVendorSpec
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject

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

// pty4j starts every program through an executable of its jar (pty4j-unix-spawn-helper, extracted when it runs), which JetBrains signed without the
// hardened runtime: Apple refuses to notarize an application that has one, even inside a jar, and Compose only signs the libraries of the jars
// (.dylib and .jnilib). So when the application is signed, the jars of its runtime classpath go through this transform, which signs that
// executable again, with the identity that Compose signs with and the hardened runtime. A jar without it is given as it is.
abstract class SignSpawnHelper : TransformAction<SignSpawnHelper.Parameters> {
    interface Parameters : TransformParameters {
        @get:Input
        val identity: Property<String>

        @get:Input
        @get:Optional
        val keychain: Property<String>
    }

    @get:Inject
    abstract val exec: ExecOperations

    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val input: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val jar = input.get().asFile
        val helper = ZipFile(jar).use { zip -> zip.getEntry(SPAWN_HELPER)?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } } }
        if (helper == null) {
            outputs.file(input)
            return
        }
        val unpacked = Files.createTempFile("pty4j-unix-spawn-helper", "")
        try {
            Files.write(unpacked, helper)
            exec.exec {
                commandLine(
                    listOf("codesign", "--force", "--options", "runtime", "--timestamp", "--sign", parameters.identity.get()) +
                        (parameters.keychain.orNull?.let { listOf("--keychain", it) } ?: emptyList()) +
                        unpacked.toString()
                )
            }
            val signed = Files.readAllBytes(unpacked)
            ZipFile(jar).use { zip ->
                ZipOutputStream(outputs.file(jar.name).outputStream().buffered()).use { out ->
                    for (entry in zip.entries()) {
                        out.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                        if (entry.name == SPAWN_HELPER) out.write(signed) else zip.getInputStream(entry).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
            }
        } finally {
            Files.deleteIfExists(unpacked)
        }
    }

    private companion object {
        const val SPAWN_HELPER = "resources/com/pty4j/native/darwin/pty4j-unix-spawn-helper"
    }
}

// The properties that turn Compose's signing on and name its identity (in ~/.gradle/gradle.properties or given with -P), read as Compose reads them:
// an identity without its prefix is a Developer ID.
val macSigningIdentity = providers.gradleProperty("compose.desktop.mac.signing.identity").map { identity ->
    if (identity.startsWith("Developer ID Application: ")) identity else "Developer ID Application: $identity"
}
val macSigningKeychain = providers.gradleProperty("compose.desktop.mac.signing.keychain")
if (providers.gradleProperty("compose.desktop.mac.sign").orNull.toBoolean()) {
    val spawnHelperSigned = Attribute.of("app.katty.spawnHelperSigned", Boolean::class.javaObjectType)
    dependencies {
        attributesSchema { attribute(spawnHelperSigned) }
        artifactTypes.getByName("jar") { attributes.attribute(spawnHelperSigned, false) }
        registerTransform(SignSpawnHelper::class) {
            from.attribute(spawnHelperSigned, false).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
            to.attribute(spawnHelperSigned, true).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
            parameters {
                identity = macSigningIdentity
                keychain = macSigningKeychain
            }
        }
    }
    // The classpath that Compose packages.
    configurations.runtimeClasspath { attributes.attribute(spawnHelperSigned, true) }
}

// A complete notarization (`notarize`): Compose's notarizes the disk image only, unsigned, and staples the ticket to it, so the application that
// is copied out of it had none, and macOS asked Apple at its first launch (refused offline). So the application is notarized and stapled first,
// then put in the disk image, which is signed, notarized and stapled by Compose's `notarizeDmg`. Two submissions to Apple: each takes minutes,
// the first ones of a team took more than an hour. The credentials are Compose's properties, as for `notarizeDmg`, and are only read when a task
// runs: a build without them configures these tasks as any other (`./gradlew tasks`).
abstract class NotarizeApp : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val script: RegularFileProperty

    @get:Internal
    abstract val app: DirectoryProperty

    @get:Internal
    abstract val appleId: Property<String>

    @get:Internal
    abstract val teamId: Property<String>

    @get:Internal
    abstract val password: Property<String>

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun notarize() {
        exec.exec {
            commandLine("sh", script.get().asFile, app.get().asFile, appleId.required("appleID"), teamId.required("teamID"))
            // Not on the command line, which any process of the machine can read.
            environment("KATTY_NOTARIZATION_PASSWORD", password.required("password"))
        }
    }

    private fun Property<String>.required(name: String): String =
        orNull ?: throw GradleException("Notarizing needs the Gradle property compose.desktop.mac.notarization.$name")
}

// The disk image that jpackage makes is not signed (only the application in it is), which Apple recommends.
abstract class SignDmg : DefaultTask() {
    @get:Internal
    abstract val dmg: RegularFileProperty

    @get:Internal
    abstract val identity: Property<String>

    @get:Internal
    abstract val keychain: Property<String>

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun sign() {
        val identity = identity.orNull
            ?: throw GradleException("Signing needs the Gradle properties compose.desktop.mac.sign and compose.desktop.mac.signing.identity")
        exec.exec {
            commandLine(
                listOf("codesign", "--force", "--timestamp", "--sign", identity) +
                    (keychain.orNull?.let { listOf("--keychain", it) } ?: emptyList()) +
                    dmg.get().asFile.path
            )
        }
    }
}

// Submissions to Apple and signatures, whose results are not files that Gradle could compare: both are made each time they are asked for.
val notarizeApp = tasks.register<NotarizeApp>("notarizeApp") {
    group = "compose desktop"
    description = "Notarizes the application that createDistributable makes, and staples the ticket to it."
    dependsOn("createDistributable")
    outputs.upToDateWhen { false }
    script = layout.projectDirectory.file("notarize-app.sh")
    app = layout.buildDirectory.dir("compose/binaries/main/app/KaTTY.app")
    appleId = providers.gradleProperty("compose.desktop.mac.notarization.appleID")
    teamId = providers.gradleProperty("compose.desktop.mac.notarization.teamID")
    password = providers.gradleProperty("compose.desktop.mac.notarization.password")
}

val signDmg = tasks.register<SignDmg>("signDmg") {
    group = "compose desktop"
    description = "Signs the disk image that packageDmg makes, with the identity of the application."
    dependsOn("packageDmg")
    outputs.upToDateWhen { false }
    dmg = layout.buildDirectory.file("compose/binaries/main/dmg/KaTTY-${project.version}.dmg")
    identity = macSigningIdentity
    keychain = macSigningKeychain
}

// The disk image is made of the application once it is stapled. Compose registers its tasks once this script has run, so they are taken as they come.
tasks.matching { it.name == "packageDmg" }.configureEach { mustRunAfter(notarizeApp) }
tasks.matching { it.name == "notarizeDmg" }.configureEach {
    dependsOn(signDmg)
    // Compose's notarization settings have a property that the configuration cache cannot store (`ascProvider`), whatever is set.
    notCompatibleWithConfigurationCache("Compose's notarization settings cannot be stored in the configuration cache")
}

tasks.register("notarize") {
    group = "compose desktop"
    description = "Notarizes the application and the signed disk image that holds it, and staples a ticket to each."
    dependsOn(notarizeApp, "notarizeDmg")
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
    // The terminals take the user's $SHELL for their shell, and the tests that start `/bin/sh -c` take it for a program, which they cannot
    // where the shell is bash (a CI machine's): macOS's /bin/sh runs bash, and its process has that name. The default of macOS is zsh.
    environment("SHELL", "/bin/zsh")
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
                bundleID = "net.kodein.app.katty"
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
