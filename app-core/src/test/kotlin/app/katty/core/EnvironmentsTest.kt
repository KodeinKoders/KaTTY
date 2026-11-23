package app.katty.core

import app.katty.core.EnvironmentProblemKind.INVALID_LINE
import app.katty.core.EnvironmentProblemKind.INVALID_NAME
import app.katty.core.EnvironmentProblemKind.RESERVED_VARIABLE
import app.katty.core.EnvironmentProblemKind.UNDEFINED_REFERENCE
import app.katty.core.EnvironmentProblemKind.UNSUPPORTED_EXPANSION
import app.katty.core.EnvironmentProblemKind.UNTERMINATED_QUOTE
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvironmentsTest {
    private val root: Path = Files.createTempDirectory("katty-environments")
    private val home = "/home/me"
    private val shared = folder("shared")
    private val secrets = folder("secrets")
    private val directories = listOf(shared.toString(), secrets.toString())

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun folder(name: String) = root.resolve(name).also { it.createDirectories() }

    private fun write(folder: Path, name: String, text: String) = folder.resolve(name).also { it.writeText(text) }

    private fun resolve(name: String?, base: Map<String, String> = emptyMap()) = Environments.resolve(name, directories, base, home)

    private fun resolved(name: String, base: Map<String, String> = emptyMap()) = assertIs<EnvironmentResolution.Resolved>(resolve(name, base))

    private fun variables(text: String, base: Map<String, String> = emptyMap()): Map<String, String> {
        write(shared, "env.env", text)
        return resolved("env", base).change.variables
    }

    @Test
    fun noEnvironmentIsTheStandardOne() {
        assertEquals(EnvironmentResolution.Standard, resolve(null))
        assertTrue(resolve(null).change.isEmpty)
    }

    @Test
    fun anEnvironmentWithNoFileIsMissing() {
        assertEquals(EnvironmentResolution.Missing("sunday"), resolve("sunday"))
        assertTrue(resolve("sunday").change.isEmpty)
    }

    @Test
    fun aNameThatIsNotAFileNameIsMissing() {
        write(shared, "a.env", "A=1")

        assertEquals(EnvironmentResolution.Missing("../shared/a"), resolve("../shared/a"))
    }

    @Test
    fun linesAreReadWithCommentsAndExport() {
        val read = variables(
            """
            # A comment
              A=1
            export B=two words
            C = spaced
            D=
            E=value # a comment
            F=value#not a comment
            """.trimIndent(),
        )

        assertEquals(mapOf("A" to "1", "B" to "two words", "C" to "spaced", "D" to "", "E" to "value", "F" to "value#not a comment"), read)
    }

    @Test
    fun doubleQuotesExpandAndEscape() {
        val read = variables(
            """
            A="x\ny\t\"q\" \\ \${'$'}HOME"
            B="${'$'}{HOME}/bin:${'$'}PATH"
            C="# not a comment" # a comment
            """.trimIndent(),
            base = mapOf("HOME" to "/h", "PATH" to "/usr/bin"),
        )

        assertEquals("x\ny\t\"q\" \\ \$HOME", read["A"])
        assertEquals("/h/bin:/usr/bin", read["B"])
        assertEquals("# not a comment", read["C"])
    }

    @Test
    fun singleQuotesAreLiteral() {
        val read = variables("A='${'$'}{HOME} \\n # x'", base = mapOf("HOME" to "/h"))

        assertEquals("${'$'}{HOME} \\n # x", read["A"])
    }

    @Test
    fun aQuotedValueGoesOnOverLines() {
        val read = variables("A=\"one\ntwo\"\nB=3")

        assertEquals(mapOf("A" to "one\ntwo", "B" to "3"), read)
    }

    @Test
    fun aTildeStartsAnUnquotedValueAsHome() {
        val read = variables("A=~/.claude-sunday\nB=~\nC=\"~/x\"\nD=a~/b")

        assertEquals("$home/.claude-sunday", read["A"])
        assertEquals(home, read["B"])
        assertEquals("~/x", read["C"])
        assertEquals("a~/b", read["D"])
    }

    @Test
    fun aReferenceIsToALineAboveThenToTheBase() {
        val read = variables("A=one\nB=\$A-\${C}\nC=three", base = mapOf("C" to "base"))

        assertEquals("one-base", read["B"])
        assertEquals("three", read["C"])
    }

    @Test
    fun anUndefinedReferenceIsEmptyAndReportedByName() {
        write(shared, "env.env", "A=x\${NOPE}y")

        val resolution = resolved("env")

        assertEquals("xy", resolution.change.variables["A"])
        assertEquals(listOf(EnvironmentProblem(shared.resolve("env.env"), 1, UNDEFINED_REFERENCE)), resolution.problems, "Not by its name")
    }

    @Test
    fun theFilesOfTheFoldersAreMergedInTheirOrder() {
        write(shared, "sunday.env", "A=shared\nB=shared\nD=\$A")
        write(secrets, "sunday.env", "B=secret\nC=\$A")

        val resolution = resolved("sunday")

        assertEquals(mapOf("A" to "shared", "B" to "secret", "D" to "shared", "C" to "shared"), resolution.change.variables)
        assertEquals(listOf(shared.resolve("sunday.env"), secrets.resolve("sunday.env")), resolution.files)
    }

    @Test
    fun unsetTakesAVariableOutUntilItIsSetAgain() {
        write(shared, "env.env", "A=1\nB=2\nunset A ANTHROPIC_API_KEY # the client's account\nC=\${ANTHROPIC_API_KEY}")
        write(secrets, "env.env", "B=3\nunset B\nB=4")

        val change = resolved("env", base = mapOf("ANTHROPIC_API_KEY" to "key")).change

        assertEquals(mapOf("B" to "4", "C" to ""), change.variables)
        assertEquals(setOf("A", "ANTHROPIC_API_KEY"), change.unset)
    }

    @Test
    fun theChangeIsMadeToAnEnvironment() {
        val base = mutableMapOf("A" to "old", "B" to "gone", "C" to "kept")

        EnvironmentChange(mapOf("A" to "new"), setOf("B")).applyTo(base)

        assertEquals(mapOf("A" to "new", "C" to "kept"), base)
    }

    @Test
    fun theVariablesOfTheTerminalCannotBeChanged() {
        write(shared, "env.env", "TERM=dumb\nKATTY_X=1\nunset TERM_PROGRAM COLORTERM OK\nZDOTDIR=/z\nOK2=1")

        val resolution = resolved("env")

        assertEquals(mapOf("ZDOTDIR" to "/z", "OK2" to "1"), resolution.change.variables)
        assertEquals(setOf("OK"), resolution.change.unset)
        assertEquals(
            listOf(1 to "TERM", 2 to "KATTY_X", 3 to "TERM_PROGRAM", 3 to "COLORTERM"),
            resolution.problems.map { it.line to it.variable },
        )
        assertTrue(resolution.problems.all { it.kind == RESERVED_VARIABLE })
    }

    @Test
    fun aLineThatCannotBeReadIsLeftOut() {
        write(
            shared,
            "env.env",
            """
            OK1=1
            not a line
            1BAD=x
            B=${'$'}(whoami)
            C=${'$'}{D:-x}
            E="closed" trailing
            unset 2BAD
            A="unterminated
            OK2=2
            """.trimIndent(),
        )

        val resolution = resolved("env")

        assertEquals(mapOf("OK1" to "1", "OK2" to "2"), resolution.change.variables)
        assertEquals(
            listOf(
                2 to INVALID_LINE,
                3 to INVALID_NAME,
                4 to UNSUPPORTED_EXPANSION,
                5 to UNSUPPORTED_EXPANSION,
                6 to INVALID_LINE,
                7 to INVALID_NAME,
                8 to UNTERMINATED_QUOTE,
            ),
            resolution.problems.map { it.line to it.kind },
        )
    }

    @Test
    fun nothingThatIsReportedHoldsAValue() {
        val secret = "s3cr3t"
        write(shared, "env.env", "SECRET=$secret\n$secret\nX=\"$secret\nY=\$($secret)\nZ='$secret' $secret\nW=\${$secret:-x}\nV=\${NOPE}$secret\nP=pre\$$secret\nQ=\"\${$secret}\"")

        val resolution = resolved("env")
        val listing = Environments.list(directories, home)

        assertEquals(secret, resolution.change.variables["SECRET"])
        assertFalse(secret in resolution.toString(), resolution.toString())
        assertFalse(secret in resolution.change.toString())
        assertFalse(secret in resolution.problems.toString())
        assertFalse(secret in listing.toString())
        assertEquals(listOf(2, 3, 4, 5, 6, 7, 8, 9), resolution.problems.map { it.line })
    }

    @Test
    fun theListingHasTheEnvironmentsOfTheFoldersByName() {
        write(shared, "sunday.env", "A=1")
        write(secrets, "sunday.env", "B=1\nbad\nworse")
        write(shared, "kodein.env", "A=1")
        write(shared, ".hidden.env", "A=1")
        write(shared, ".env", "A=1")
        write(shared, "notes.txt", "A=1")
        folder("shared/folder.env")

        val listing = Environments.list(directories + "${root}/nowhere", home)

        assertEquals(listOf("kodein", "sunday"), listing.environments.map { it.name })
        val sunday = listing.environments.last().files
        assertEquals(listOf(shared.resolve("sunday.env"), secrets.resolve("sunday.env")), sunday.map { it.path })
        assertEquals(listOf(0, 2), sunday.map { it.ignoredLines })
        assertEquals(listOf(null, 2), sunday.map { it.firstIgnoredLine })
    }

    @Test
    fun theFoldersStartWithHome() {
        assertEquals("$home/.config/KaTTY/environments", Environments.expandHome(Environments.DEFAULT_DIRECTORY, home))
        assertEquals("/abs", Environments.expandHome("/abs", home))
    }

    private val kosi = EnvironmentFolder("~/dev/kosi-libs", "kodein")
    private val vie = EnvironmentFolder("~/dev/kosi-libs/vie", "perso")
    private val koin = EnvironmentFolder("/home/me/dev/koin/", "koin")

    private fun ruleOf(directory: String, vararg rules: EnvironmentFolder) = Environments.folderOf(directory, rules.toList(), home)

    @Test
    fun aProjectIsUnderTheFolderItIsInOrBelow() {
        assertEquals(kosi, ruleOf("$home/dev/kosi-libs", kosi), "The folder itself")
        assertEquals(kosi, ruleOf("$home/dev/kosi-libs/api", kosi))
        assertEquals(kosi, ruleOf("$home/dev/kosi-libs/api/core", kosi), "Any depth")
        assertEquals(kosi, ruleOf("$home/dev/kosi-libs/", kosi), "Its own trailing slash does not matter")
        assertEquals(koin, ruleOf("$home/dev/koin/app", koin), "The slash that ends the rule's folder does not matter")
        assertEquals(koin, ruleOf("~/dev/koin", koin), "`~` is the home, in a directory too")
    }

    @Test
    fun aProjectIsNotUnderAFolderThatOnlyStartsLikeItsOwn() {
        assertNull(ruleOf("$home/dev/kosi-libs2", kosi), "Whole names")
        assertNull(ruleOf("$home/dev/kosi-lib", kosi))
        assertNull(ruleOf("$home/dev", kosi), "Not a folder above it")
        assertNull(ruleOf("$home/dev/Kosi-Libs", kosi), "Case counts")
        assertNull(ruleOf("$home/dev/kosi-libs"), "No rule at all")
    }

    @Test
    fun theDeepestFolderWinsWhateverTheOrderOfTheRules() {
        assertEquals(vie, ruleOf("$home/dev/kosi-libs/vie/app", kosi, vie))
        assertEquals(vie, ruleOf("$home/dev/kosi-libs/vie/app", vie, kosi))
        assertEquals(kosi, ruleOf("$home/dev/kosi-libs/other", vie, kosi))
        assertEquals(kosi, ruleOf("$home/dev/kosi-libs/vie2", vie, kosi), "vie2 is not vie")
    }

    @Test
    fun theFirstRuleOfAFolderThatHasTwoWins() {
        val again = kosi.copy(environment = "other")

        assertEquals(kosi, ruleOf("$home/dev/kosi-libs/api", kosi, again))
        assertEquals(again, ruleOf("$home/dev/kosi-libs/api", again, kosi))
    }

    @Test
    fun aRuleWithNoFolderCoversNothingAndTheRootCoversEverything() {
        assertNull(ruleOf("$home/dev/api", EnvironmentFolder("", "x"), EnvironmentFolder("  ", "y")))
        val root = EnvironmentFolder("/", "all")
        assertEquals(root, ruleOf("$home/dev/api", root))
        assertEquals(kosi, ruleOf("$home/dev/kosi-libs/api", root, kosi))
    }

    @Test
    fun theFolderOfAProjectGivesItsEnvironmentElseTheDefaultOne() {
        val settings = Settings(defaultEnvironment = "default", environmentFolders = listOf(kosi, EnvironmentFolder("~/dev/private", null)))

        assertEquals("kodein", settings.environmentForProject("$home/dev/kosi-libs/api", home))
        assertEquals("default", settings.environmentForProject("$home/dev/other", home), "No folder: the default one")
        assertNull(settings.environmentForProject("$home/dev/private/api", home), "A folder that says none is not the default one")
        assertNull(Settings().environmentForProject("$home/dev/other", home))
    }

    @Test
    fun aShellIsToldItsEnvironmentAndAStandardOneIsNotTold() {
        val change = EnvironmentChange(mapOf("A" to "1"), setOf("B"))

        assertEquals(EnvironmentChange(mapOf("A" to "1", "KATTY_ENVIRONMENT" to "kodein"), setOf("B")), change.named("kodein"))
        assertEquals(EnvironmentChange(mapOf("A" to "1"), setOf("B", "KATTY_ENVIRONMENT")), change.named(null), "Taken out of KaTTY's own")
        assertEquals(EnvironmentChange(emptyMap(), setOf("KATTY_ENVIRONMENT")), EnvironmentChange.NONE.named(null))
        assertEquals(EnvironmentChange(mapOf("KATTY_ENVIRONMENT" to "kodein"), emptySet()), EnvironmentChange.NONE.named("kodein"))
        assertEquals(change.named("x"), change.named(null).named("x"), "Never both set and taken out")
        assertEquals(change.named(null), change.named("x").named(null))
    }

    @Test
    fun theNameOfTheEnvironmentIsAVariableThatAFileCannotSet() {
        assertTrue(Environments.isReserved(Environments.NAME_VARIABLE))
        assertEquals(emptyMap(), variables("KATTY_ENVIRONMENT=other"), "It is KaTTY's to say")
    }
}
