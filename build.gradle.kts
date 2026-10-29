plugins {
    alias(libs.plugins.buildconfig) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    // Applied, not `apply false`: the root owns the `komple { }` extension that installs Zig, which ghostty-vt uses to build libghostty-vt.
    alias(libs.plugins.komple)
    alias(libs.plugins.komple.tool.zig)
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.plugin.compose) apply false
    alias(libs.plugins.kotlin.plugin.serialization) apply false
}

allprojects {
    group = "app.katty"
    version = "0.1.0"
}

komple {
    zig {
        // The Zig that Ghostty asks for: it only builds with that minor version, and the one it lists is the one its own CI uses. Read from the
        // submodule, so that updating Ghostty updates Zig with it.
        version = providers.provider {
            val zon = layout.projectDirectory.file("ghostty/build.zig.zon").asFile.readText()
            Regex("""\.minimum_zig_version\s*=\s*"([^"]+)"""").find(zon)?.groupValues?.get(1)
                ?: error("ghostty/build.zig.zon has no minimum_zig_version: is the ghostty submodule checked out?")
        }
        // The minisign key of the Zig project, which verifies the download. https://ziglang.org/download/
        publicKey = "RWSGOq2NVecA2UPNdBUZykf1CCb147pkmdtYxgb3Ti+JO/wCYvhbAb/U"
    }
}
