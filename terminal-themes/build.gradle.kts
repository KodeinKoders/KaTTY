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

        // What a color scheme picker shows of each scheme, so that it does not parse 811 property lists to draw its list:
        // name, dark (1) or light (0), background, foreground, then the red, green, yellow, blue, magenta and cyan of the palette (ANSI 1 to 6),
        // as RRGGBB, separated by tabs. It is read the way `parseITermColorScheme` reads a color, and a test checks that they agree.
        val colorEntry = Regex("<key>([^<]+)</key>\\s*<dict>(.*?)</dict>", RegexOption.DOT_MATCHES_ALL)
        fun component(body: String, name: String): Int {
            val value = Regex("<key>$name</key>\\s*<real>([^<]+)</real>").find(body)!!.groupValues[1].toDouble()
            return Math.round(value * 255).toInt().coerceIn(0, 255)
        }
        val rows = names.map { name ->
            val colors = colorEntry.findAll(directory.resolve("$name.itermcolors").readText()).associate { match ->
                val body = match.groupValues[2]
                match.groupValues[1] to listOf("Red", "Green", "Blue").map { component(body, "$it Component") }
            }
            fun hex(key: String) = colors.getValue(key).joinToString("") { "%02X".format(it) }
            val (red, green, blue) = colors.getValue("Background Color")
            // Dark when the background's brightness (the weights of the sRGB primaries) is under half.
            val dark = if ((0.2126 * red + 0.7152 * green + 0.0722 * blue) < 127.5) 1 else 0
            (listOf(name, dark.toString(), hex("Background Color"), hex("Foreground Color")) + (1..6).map { hex("Ansi $it Color") }).joinToString("\t")
        }
        directory.resolve("index.tsv").writeText(rows.joinToString("\n", postfix = "\n"))
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
