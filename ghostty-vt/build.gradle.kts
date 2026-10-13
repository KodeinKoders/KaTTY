plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(findProperty("jvm.toolchain")!!.toString().toInt())
    explicitApi()
}

// Packs the libghostty-vt dylibs into the jar, as app/katty/ghostty/native/<os>-<arch>/, which is
// where NativeLibrary looks for them.
val nativeLibraries = layout.buildDirectory.dir("generated/native")
val prepareNativeLibraries by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory.dir("libghostty-vt/lib"))
    into(nativeLibraries.map { it.dir("app/katty/ghostty/native") })
    // The file name carries the library version (libghostty-vt.0.1.0.dylib), but NativeLibrary asks
    // for a fixed name, so that updating the library does not mean updating the code.
    rename { it.replace(Regex("""^libghostty-vt\.\d+(\.\d+)*\.(\w+)$"""), "libghostty-vt.$2") }
}

sourceSets.main {
    resources.srcDir(nativeLibraries)
}

tasks.processResources {
    dependsOn(prepareNativeLibraries)
}

tasks.test {
    // The FFM API is restricted: without this flag, the JVM warns on the first native call.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    testImplementation(libs.kotlin.test.junit)
}
