plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    // The JetBrains Runtime, which the application ships with: what is tested is what runs.
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(findProperty("jvm.toolchain")!!.toString().toInt())
        vendor = JvmVendorSpec.JETBRAINS
    }
    explicitApi()
}

// The test of macOS's secure keyboard entry asks the system whether this process keeps the keys, and once a test of the same JVM has drawn with
// Skia (TerminalViewTest) the system says it does not, though it was turned on (measured; why was not found). It runs in a JVM of its own.
val secureKeyboardTests = "app.katty.terminal.compose.platform.SecureKeyboardTest"

tasks.withType<Test>().configureEach {
    // The tests render with Skia, in memory: no display is needed.
    systemProperty("java.awt.headless", "true")
    // ghostty-vt calls the FFM API, and pty4j calls JNA: both are restricted, and the JVM warns on the
    // first native call without this flag.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// The tests that do not run on Windows, and why: the others run there.
val notOnWindows = mapOf(
    "app.katty.terminal.compose.TerminalTest" to "76 of its 78 tests start their terminal with /bin/sh, which TestProgram of terminal's tests would replace",
    "app.katty.terminal.compose.TerminalDropTest" to "the paths of a Unix system, and /bin/sh",
    "app.katty.terminal.compose.CommandMarksUiTest.theTooltipOfACommandThatWasStoppedSaysSoAndHowLongItRan" to "no job is stopped on Windows",
    "app.katty.terminal.compose.TerminalViewTest.aCommandThatWasStoppedIsYellowAndNotRed" to "no job is stopped on Windows",
)

tasks.test {
    filter {
        excludeTestsMatching(secureKeyboardTests)
        if (System.getProperty("os.name").startsWith("Windows")) notOnWindows.keys.forEach(::excludeTestsMatching)
    }
}

val secureKeyboardTest = tasks.register<Test>("secureKeyboardTest") {
    group = "verification"
    description = "Runs the test of secure keyboard entry in a JVM where nothing was drawn."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching(secureKeyboardTests) }
}

tasks.check {
    dependsOn(secureKeyboardTest)
}

dependencies {
    // Terminal() takes a TerminalSession, a Modifier, and is a composable.
    api(projects.terminal)

    api(libs.compose.runtime)
    api(libs.compose.ui)

    implementation(libs.compose.foundation)

    implementation(libs.slf4j.api)

    testImplementation(compose.desktop.currentOs)

    testImplementation(libs.kotlin.test.junit)

    testRuntimeOnly(libs.slf4j.simple)
}
