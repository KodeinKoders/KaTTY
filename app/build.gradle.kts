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
    // The version of the project, declared once in the root build script: the About panel says it. It is not the bundle's `packageVersion`
    // below, which macOS wants to start with 1 or more.
    buildConfigField("VERSION", project.version.toString())
    // The short hash of the commit it is built from, which tells two builds of the same version apart; "unknown" outside a git checkout.
    val commit = providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }.orElse("unknown")
    buildConfigField("GIT_COMMIT", commit)
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
    testImplementation(compose.desktop.uiTestJUnit4)
}

// The logo of the repository is the icon of the windows, and of the Dock while the application runs: it is a resource of the application.
tasks.processResources {
    from(rootProject.file("logo.png")) { into("app/katty") }
}

// The icon of the application that is packaged (the bundle and its disk image) is made from the same logo, with the tools of macOS: the only platform
// that the application is packaged for.
val macIcon = tasks.register<Exec>("macIcon") {
    val logo = rootProject.file("logo.png")
    val icns = layout.buildDirectory.file("icons/KaTTY.icns")
    inputs.file(logo)
    inputs.file("make-icns.sh")
    outputs.file(icns)
    commandLine("sh", "make-icns.sh", logo.absolutePath, icns.get().asFile.absolutePath)
}

tasks.withType<AbstractJPackageTask>().configureEach {
    dependsOn(macIcon)
}

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
            // macOS does not accept a version whose first number is 0.
            packageVersion = "1.0.0"
            description = "A terminal manager for coding agents"
            vendor = "Kodein Koders"
            // The FFM calls of ghostty-vt and the JNA calls of pty4j are restricted.
            jvmArgs += "--enable-native-access=ALL-UNNAMED"

            macOS {
                bundleID = "app.katty"
                dockName = "KaTTY"
                iconFile.set(layout.buildDirectory.file("icons/KaTTY.icns"))
            }
        }
    }
}
