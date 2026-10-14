import org.gradle.jvm.toolchain.JvmVendorSpec

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.compose.multiplatform)
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
    }
}
