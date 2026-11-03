package app.katty

import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeDescriptionTest {
    private val jbr = mapOf(
        "java.vendor" to "JetBrains s.r.o.",
        "java.vendor.version" to "JBR-25.0.3+9-329.124-jcef",
        "java.runtime.name" to "OpenJDK Runtime Environment",
        "java.runtime.version" to "25.0.3+9-b329.124",
        "java.vm.name" to "OpenJDK 64-Bit Server VM",
        "java.home" to "/Applications/KaTTY.app/Contents/runtime/Contents/Home",
    )

    @Test
    fun saysTheVendorTheRuntimeTheMachineAndWhereItIs() {
        assertEquals(
            "JetBrains s.r.o. JBR-25.0.3+9-329.124-jcef (OpenJDK Runtime Environment 25.0.3+9-b329.124, OpenJDK 64-Bit Server VM) in /Applications/KaTTY.app/Contents/runtime/Contents/Home",
            runtimeDescription(jbr::get),
        )
    }

    @Test
    fun aRuntimeWithoutAVersionOfItsVendorHasNone() {
        val openJdk = jbr + mapOf("java.vendor" to "Oracle Corporation", "java.home" to "/opt/jdk-25") - "java.vendor.version"

        assertEquals("Oracle Corporation (OpenJDK Runtime Environment 25.0.3+9-b329.124, OpenJDK 64-Bit Server VM) in /opt/jdk-25", runtimeDescription(openJdk::get))
    }

    @Test
    fun theRuntimeThatRunsTheTestsIsDescribed() {
        // The tests run on the toolchain's runtime, which is the JetBrains Runtime.
        assertEquals(true, runtimeDescription().startsWith("JetBrains s.r.o. JBR-"), runtimeDescription())
    }
}
