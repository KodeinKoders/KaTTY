plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(findProperty("jvm.toolchain")!!.toString().toInt())
    explicitApi()
}

tasks.test {
    // The tests render with Skia, in memory: no display is needed.
    systemProperty("java.awt.headless", "true")
    // ghostty-vt calls the FFM API, and pty4j calls JNA: both are restricted, and the JVM warns on the
    // first native call without this flag.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    // Terminal() takes a TerminalSession, a Modifier, and is a composable.
    api(projects.terminal)

    api(libs.compose.runtime)
    api(libs.compose.ui)

    implementation(libs.compose.foundation)

    testImplementation(compose.desktop.currentOs)

    testImplementation(libs.kotlin.test.junit)

    testRuntimeOnly(libs.slf4j.simple)
}
