plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.serialization)
}

kotlin {
    jvmToolchain(findProperty("jvm.toolchain")!!.toString().toInt())
    explicitApi()
}

// Packs the color schemes into the jar, as app/katty/terminal/themes/schemes/, with an index of their
// names: a jar's directory cannot be listed reliably, and the names are what the user chooses from.
val colorSchemes = layout.buildDirectory.dir("generated/schemes")
val prepareColorSchemes = tasks.register<Sync>("prepareColorSchemes") {
    val schemes = rootProject.layout.projectDirectory.dir("iterm2-color-schemes")
    from(schemes.dir("schemes"))
    from(schemes.file("LICENSE")) { rename { "LICENSE-iterm2-color-schemes" } }
    into(colorSchemes.map { it.dir("app/katty/terminal/themes/schemes") })

    doLast {
        val directory = destinationDir
        val names = directory.listFiles { file -> file.extension == "itermcolors" }!!
            .map { it.nameWithoutExtension }
            .sortedWith(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()))
        directory.resolve("index.txt").writeText(names.joinToString("\n", postfix = "\n"))
    }
}

sourceSets.main {
    resources.srcDir(colorSchemes)
}

tasks.processResources {
    dependsOn(prepareColorSchemes)
}

dependencies {
    // TerminalTheme and TerminalColor are part of the API.
    api(projects.terminal)

    implementation(libs.kotlinx.serialization.core)
    implementation(libs.xmlutil.serialization)

    testImplementation(libs.kotlin.test.junit)
}
