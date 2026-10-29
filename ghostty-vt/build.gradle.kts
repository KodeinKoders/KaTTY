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
            args(
                "build",
                "-Demit-lib-vt",
                "-Doptimize=ReleaseFast",
                // The only platform that KaTTY runs on, whatever the machine that builds it. No xcframework either: it needs xcodebuild.
                "-Dtarget=aarch64-macos",
                "-Demit-xcframework=false",
                "--prefix", output.absolutePath,
                "--cache-dir", cacheDirectory.get().dir("local").asFile.absolutePath,
                "--global-cache-dir", cacheDirectory.get().dir("global").asFile.absolutePath,
            )
        }
    }
}

val ghosttySubmodule = rootProject.layout.projectDirectory.dir("ghostty")

val buildLibGhosttyVt = tasks.register<BuildLibGhosttyVt>("buildLibGhosttyVt") {
    group = "build"
    description = "Builds libghostty-vt from the ghostty submodule with the Zig that Ghostty asks for."
    zigDirectory = komple.tools.zig.installDirectory
    ghosttyDirectory = ghosttySubmodule
    sources.from(
        listOf("build.zig", "build.zig.zon", "src", "pkg", "vendor", "include").map { ghosttySubmodule.asFile.resolve(it) },
    )
    cacheDirectory = layout.buildDirectory.dir("zig-cache")
    outputDirectory = layout.buildDirectory.dir("libghostty-vt")
}

// Packs the dylib into the jar, as app/katty/ghostty/native/<os>-<arch>/, which is where NativeLibrary looks for it.
val nativeLibraries = layout.buildDirectory.dir("generated/native")
val prepareNativeLibraries = tasks.register<Sync>("prepareNativeLibraries") {
    group = "build"
    description = "Packs the libghostty-vt dylib into the resources, where NativeLibrary looks for it."
    // Only the versioned file (libghostty-vt.0.1.0.dylib), not the links to it nor the static library.
    from(buildLibGhosttyVt.flatMap { it.outputDirectory.dir("lib") }) { include("libghostty-vt.*.*.*.dylib") }
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
