import java.io.ByteArrayOutputStream
import javax.inject.Inject

plugins {
    alias(libs.plugins.komple)
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(findProperty("jvm.toolchain")!!.toString().toInt())
    explicitApi()
}

// Builds libghostty-vt from the ghostty submodule with the Zig that komple installs (see the root build script). The result is a cacheable task
// of its own: Zig fetches Ghostty's dependencies on the first build, which is why its caches are in the build directory and not outputs.
abstract class BuildLibGhosttyVt @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    /** Where komple installed Zig: its executable is at the root. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val zigDirectory: DirectoryProperty

    /** What the library is built from: the submodule, without its documentation and its macOS and GTK applications. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** The submodule's checkout, where `zig build` runs. Not an input as a whole: [sources] is. */
    @get:Internal
    abstract val ghosttyDirectory: DirectoryProperty

    /** Zig's caches, which hold the dependencies that it downloaded. */
    @get:Internal
    abstract val cacheDirectory: DirectoryProperty

    /** What is given to `zig build` to say what is built, which is part of what identifies the library (see [StampLibGhosttyVt]). */
    @get:Input
    abstract val options: ListProperty<String>

    /** The install prefix: `lib/` has the library and `include/` its headers. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun build() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        exec.exec {
            executable = zigDirectory.get().file("zig").asFile.absolutePath
            workingDir = ghosttyDirectory.get().asFile
            args(listOf("build") + options.get() + listOf("--prefix", output.absolutePath))
            // Zig's caches are in the build directory, not in the user's home, and not in the submodule. Zig 0.17 has no option for them.
            environment("ZIG_LOCAL_CACHE_DIR", cacheDirectory.get().dir("local").asFile.absolutePath)
            environment("ZIG_GLOBAL_CACHE_DIR", cacheDirectory.get().dir("global").asFile.absolutePath)
        }
    }
}

// What identifies the library that is built, written next to it: the commit of Ghostty, the Zig that built it and what it was built with.
// NativeLibrary hashes it into the fingerprint that the snapshots of the terminals are stamped with. The bytes of the library cannot be that
// fingerprint: two builds of the same commit are not identical (about 300 bytes of the tail change), and a snapshot is good for any of them.
abstract class StampLibGhosttyVt @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Input
    abstract val commit: Property<String>

    @get:Input
    abstract val options: ListProperty<String>

    /** Where komple installed Zig, which says its own version. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val zigDirectory: DirectoryProperty

    @get:OutputFile
    abstract val stamp: RegularFileProperty

    @TaskAction
    fun write() {
        val version = ByteArrayOutputStream()
        exec.exec {
            executable = zigDirectory.get().file("zig").asFile.absolutePath
            args("version")
            standardOutput = version
        }
        stamp.get().asFile.writeText("ghostty=${commit.get()}\nzig=${version.toString().trim()}\noptions=${options.get().joinToString(" ")}\n")
    }
}

val ghosttySubmodule = rootProject.layout.projectDirectory.dir("ghostty")

// The only platform that KaTTY runs on, whatever the machine that builds it. No xcframework either: it needs xcodebuild.
val libGhosttyVtOptions = listOf("-Demit-lib-vt", "-Doptimize=ReleaseFast", "-Dtarget=aarch64-macos", "-Demit-xcframework=false")

val buildLibGhosttyVt = tasks.register<BuildLibGhosttyVt>("buildLibGhosttyVt") {
    group = "build"
    description = "Builds libghostty-vt from the ghostty submodule with the Zig that Ghostty asks for."
    zigDirectory = komple.tools.zig.installDirectory
    ghosttyDirectory = ghosttySubmodule
    sources.from(
        listOf("build.zig", "build.zig.zon", "src", "pkg", "vendor", "include").map { ghosttySubmodule.asFile.resolve(it) },
    )
    cacheDirectory = layout.buildDirectory.dir("zig-cache")
    options = libGhosttyVtOptions
    outputDirectory = layout.buildDirectory.dir("libghostty-vt")
}

val stampLibGhosttyVt = tasks.register<StampLibGhosttyVt>("stampLibGhosttyVt") {
    group = "build"
    description = "Writes what identifies the libghostty-vt that is built: the commit of Ghostty, the Zig and the options."
    commit = providers.exec { commandLine("git", "-C", ghosttySubmodule.asFile.absolutePath, "rev-parse", "HEAD") }.standardOutput.asText.map { it.trim() }
    options = libGhosttyVtOptions
    zigDirectory = komple.tools.zig.installDirectory
    stamp = layout.buildDirectory.file("libghostty-vt-stamp/libghostty-vt.stamp")
}

// Packs the dylib into the jar, as app/katty/ghostty/native/<os>-<arch>/, which is where NativeLibrary looks for it.
val nativeLibraries = layout.buildDirectory.dir("generated/native")
val prepareNativeLibraries = tasks.register<Sync>("prepareNativeLibraries") {
    group = "build"
    description = "Packs the libghostty-vt dylib into the resources, where NativeLibrary looks for it."
    // Only the versioned file (libghostty-vt.0.1.0.dylib), not the links to it nor the static library.
    from(buildLibGhosttyVt.flatMap { it.outputDirectory.dir("lib") }) { include("libghostty-vt.*.*.*.dylib") }
    from(stampLibGhosttyVt.flatMap { it.stamp })
    into(nativeLibraries.map { it.dir("app/katty/ghostty/native/macos-aarch64") })
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
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlin.test.junit)

    testRuntimeOnly(libs.slf4j.simple)
}
