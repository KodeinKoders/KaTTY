plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.serialization)
}

kotlin {
    jvmToolchain(findProperty("jvm.toolchain")!!.toString().toInt())
    explicitApi()
}

tasks.test {
    // The sessions that the tests start call the FFM API and pty4j's JNA, which are restricted.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    // TerminalSession and what it publishes are part of the API.
    api(projects.terminal)

    implementation(libs.kotlinx.serialization.core)

    testImplementation(libs.kotlin.test.junit)

    testRuntimeOnly(libs.slf4j.simple)
}
