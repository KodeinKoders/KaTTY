plugins {
    alias(libs.plugins.buildconfig) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    // Applied, not `apply false`: the root owns the `komple { }` extension that installs Zig, which ghostty-vt uses to build libghostty-vt.
    alias(libs.plugins.komple)
    alias(libs.plugins.komple.tool.jextract)
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

// jextract, which makes the Java bindings of libghostty-vt from its headers (`:ghostty-vt:generateBindings`). Only the build for JDK 25 that the
// bindings use, and the platforms that it has; the checksums are the ones that jdk.java.net publishes next to each archive.
komple {
    jextract {
        version = libs.versions.jextract
        jdkVersion = JavaVersion.toVersion(findProperty("jvm.toolchain")!!.toString())
        checksums {
            linuxAarch64 = "0e25e6f6efa042f8758eaec65a873887fd2247fcf2e3e22dcfd7e4179fc8b0ae"
            linuxX64 = "d0cc481abc1adb16fb9514e1c5e0bfc08d38c29228bece667fb5054ceaffaa42"
            macosAarch64 = "3dd1dd1bde059d271739e2cc2290c64f93f85488c86c01e566c0e374eece798f"
            macosX64 = "6ae7a46e7e7b56f077ab72623c0a894a8d525d5b698c90785b97c241f95a99b1"
            windowsX64 = "b03533eb6b249a154752b7c7bf93cdb8c147cf2f9699422e615e84b7fb652872"
        }
    }
}
