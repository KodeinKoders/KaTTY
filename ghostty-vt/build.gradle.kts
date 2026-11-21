import java.io.ByteArrayOutputStream
import java.security.MessageDigest
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

    /** The name of Zig's executable in [zigDirectory]. */
    @get:Input
    abstract val zigExecutable: Property<String>

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
            executable = zigDirectory.get().file(zigExecutable.get()).asFile.absolutePath
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
// A copy of the sources without git (a folder that is copied to another machine to build there) has no commit: the stamp is then a hash of
// the sources that the library is built from, which changes with them as the commit would.
abstract class StampLibGhosttyVt @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    /** The commit of the submodule, empty when git cannot say it. */
    @get:Input
    abstract val commit: Property<String>

    /** What the library is built from, as [BuildLibGhosttyVt.sources]: hashed when there is no commit. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** The submodule's checkout, which the paths of [sources] are hashed relative to. */
    @get:Internal
    abstract val ghosttyDirectory: DirectoryProperty

    @get:Input
    abstract val options: ListProperty<String>

    /** Where komple installed Zig, which says its own version. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val zigDirectory: DirectoryProperty

    /** The name of Zig's executable in [zigDirectory]. */
    @get:Input
    abstract val zigExecutable: Property<String>

    @get:OutputFile
    abstract val stamp: RegularFileProperty

    @TaskAction
    fun write() {
        val version = ByteArrayOutputStream()
        exec.exec {
            executable = zigDirectory.get().file(zigExecutable.get()).asFile.absolutePath
            args("version")
            standardOutput = version
        }
        val source = commit.get().ifEmpty { null }?.let { "ghostty=$it" } ?: "ghostty-sources=${sourcesHash()}"
        stamp.get().asFile.writeText("$source\nzig=${version.toString().trim()}\noptions=${options.get().joinToString(" ")}\n")
    }

    /** A SHA-256 of the path, relative to the submodule, and the bytes of every file of [sources], in the order of the paths. */
    private fun sourcesHash(): String {
        val root = ghosttyDirectory.get().asFile.toPath()
        val digest = MessageDigest.getInstance("SHA-256")
        for (file in sources.asFileTree.files.sortedBy { root.relativize(it.toPath()).joinToString("/") }) {
            digest.update(root.relativize(file.toPath()).joinToString("/").toByteArray())
            digest.update(0)
            digest.update(file.readBytes())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

val ghosttySubmodule = rootProject.layout.projectDirectory.dir("ghostty")

/**
 * The platform that the library is built for, which is the one that builds it: the application is packaged on the platform it is for anyway.
 *
 * @property os The name of the system, as Zig's target and NativeLibrary's folder of the resources say it.
 * @property arch The name of the processor, as Zig's target and NativeLibrary's folder of the resources say it.
 * @property library Where `zig build` puts the library in its install prefix, as a pattern: the file that carries its version, not the links
 *   to it nor the static library.
 * @property extension The extension of the library, which NativeLibrary asks for.
 */
data class NativePlatform(val os: String, val arch: String, val library: String, val extension: String) {
    val zigTarget get() = "$arch-$os"
    val resourceFolder get() = "$os-$arch"
    val zigExecutable get() = if (os == "windows") "zig.exe" else "zig"

    companion object {
        fun current(): NativePlatform {
            val system = System.getProperty("os.name")
            val arch = when (val name = System.getProperty("os.arch").lowercase()) {
                "aarch64", "arm64" -> "aarch64"
                "x86_64", "amd64" -> "x86_64"
                else -> throw GradleException("libghostty-vt is not built for the processor $name")
            }
            return when {
                system.startsWith("Mac") -> NativePlatform("macos", arch, "lib/libghostty-vt.*.*.*.dylib", "dylib")
                // A DLL is put in bin/, next to the programs that would use it, and has no version in its name.
                system.startsWith("Windows") -> NativePlatform("windows", arch, "bin/ghostty-vt.dll", "dll")
                else -> throw GradleException("libghostty-vt is not built for $system yet")
            }
        }
    }
}

val nativePlatform = NativePlatform.current()

// What the library is built with. No xcframework: it needs xcodebuild, and it is only for macOS. These options are in the stamp, and so in the
// fingerprint of the snapshots: changing them on a platform makes the snapshots saved there unusable.
val libGhosttyVtOptions = listOf("-Demit-lib-vt", "-Doptimize=ReleaseFast", "-Dtarget=${nativePlatform.zigTarget}", "-Demit-xcframework=false")

// What the library is built from: the submodule, without its documentation and its macOS and GTK applications.
val libGhosttyVtSources = listOf("build.zig", "build.zig.zon", "src", "pkg", "vendor", "include").map { ghosttySubmodule.asFile.resolve(it) }

val buildLibGhosttyVt = tasks.register<BuildLibGhosttyVt>("buildLibGhosttyVt") {
    group = "build"
    description = "Builds libghostty-vt from the ghostty submodule with the Zig that Ghostty asks for."
    zigDirectory = komple.tools.zig.installDirectory
    zigExecutable = nativePlatform.zigExecutable
    ghosttyDirectory = ghosttySubmodule
    sources.from(libGhosttyVtSources)
    cacheDirectory = layout.buildDirectory.dir("zig-cache")
    options = libGhosttyVtOptions
    outputDirectory = layout.buildDirectory.dir("libghostty-vt")
}

val stampLibGhosttyVt = tasks.register<StampLibGhosttyVt>("stampLibGhosttyVt") {
    group = "build"
    description = "Writes what identifies the libghostty-vt that is built: the commit of Ghostty, the Zig and the options."
    commit = providers.exec {
        commandLine("git", "-C", ghosttySubmodule.asFile.absolutePath, "rev-parse", "HEAD")
        // Outside a checkout git fails, and says nothing on its output: the stamp hashes the sources then.
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim() }
    sources.from(libGhosttyVtSources)
    ghosttyDirectory = ghosttySubmodule
    options = libGhosttyVtOptions
    zigDirectory = komple.tools.zig.installDirectory
    zigExecutable = nativePlatform.zigExecutable
    stamp = layout.buildDirectory.file("libghostty-vt-stamp/libghostty-vt.stamp")
}

// Makes the Java bindings of libghostty-vt, and the key enums, from the headers of the submodule, with the jextract that komple installs (see the root
// build script): run it after moving the submodule, and commit the result. It has no outputs of its own, since they are sources that are checked in, and
// a task that writes what the compilation reads would be run before it.
val generateBindings = tasks.register<Exec>("generateBindings") {
    group = "build"
    description = "Regenerates the Java bindings of libghostty-vt and the key enums from the headers of the ghostty submodule."
    dependsOn(komple.tools.jextract.installTaskProvider)
    val jextract = komple.tools.jextract.installDirectory.map { it.file("bin/jextract").asFile.absolutePath }
    inputs.dir(ghosttySubmodule.dir("include"))
    outputs.upToDateWhen { false }
    workingDir = rootProject.projectDir
    commandLine("bash", "ghostty-vt/jextract.sh")
    val windows = nativePlatform.os == "windows"
    doFirst {
        // The bindings are the same for every platform (see the script), and are made where the script runs: Windows has no bash.
        if (windows) throw GradleException("Generate the bindings on macOS or Linux: they serve every platform, Windows included.")
        // Where it is is known once it is installed.
        environment("JEXTRACT", jextract.get())
    }
}

// Packs the library into the jar, as app/katty/ghostty/native/<os>-<arch>/, which is where NativeLibrary looks for it.
val nativeLibraries = layout.buildDirectory.dir("generated/native")
val prepareNativeLibraries = tasks.register<Sync>("prepareNativeLibraries") {
    group = "build"
    description = "Packs the libghostty-vt library into the resources, where NativeLibrary looks for it."
    val libraryName = "libghostty-vt.${nativePlatform.extension}"
    from(buildLibGhosttyVt.flatMap { it.outputDirectory }) {
        include(nativePlatform.library)
        eachFile { path = name }
        // The file name may carry the library version (libghostty-vt.0.1.0.dylib) or have no prefix (ghostty-vt.dll), but NativeLibrary asks
        // for a fixed name, so that updating the library does not mean updating the code.
        rename { libraryName }
    }
    from(stampLibGhosttyVt.flatMap { it.stamp })
    includeEmptyDirs = false
    into(nativeLibraries.map { it.dir("app/katty/ghostty/native/${nativePlatform.resourceFolder}") })
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
