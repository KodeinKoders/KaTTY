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

    // StateFlow is part of KattyStore's API.
    api(libs.kotlinx.coroutines)

    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    testRuntimeOnly(libs.slf4j.simple)
}
