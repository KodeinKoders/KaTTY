package app.katty

import kotlin.test.Test
import kotlin.test.assertEquals

class ClasspathTest {
    /**
     * Jewel's icons depend on IntelliJ's fork of kotlinx-coroutines, which has the same classes under another group, so both jars were on the
     * classpath. Which one wins depends on their order, and the packaged application has its jars in alphabetical order, the fork first: the code
     * compiled against kotlinx-coroutines then failed in it (`Job.cancel$default`, which the fork does not have, when the application quit).
     */
    @Test
    fun theClassesOfCoroutinesAreInOneJar() {
        val copies = ClassLoader.getSystemClassLoader().getResources("kotlinx/coroutines/Job.class").toList()

        assertEquals(1, copies.size, "kotlinx.coroutines.Job is in: $copies")
    }
}
