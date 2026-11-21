package app.katty.platform

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformBoundaryTest {
    // The packages of the implementations of a system, which only the platform package itself chooses from.
    private val implementations = Regex("""^import\s+app\.katty\.platform\.(macos|posix|linux|windows)\b""", RegexOption.MULTILINE)

    @Test
    fun onlyThePlatformPackageUsesTheImplementationsOfASystem() {
        val sources = File("src/main/kotlin/app/katty")
        assertTrue(sources.isDirectory, "The sources are found from the module's directory: ${sources.absolutePath}")
        val platform = File(sources, "platform")
        val offenders = sources.walk()
            .filter { it.isFile && it.extension == "kt" && !it.startsWith(platform) }
            .filter { implementations.containsMatchIn(it.readText()) }
            .map { it.relativeTo(sources).path }
            .toList()
        assertEquals(emptyList(), offenders, "Code outside the platform package asks the platform (desktopIntegration), not an implementation")
    }
}
