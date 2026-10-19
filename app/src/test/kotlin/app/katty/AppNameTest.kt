package app.katty

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AppNameTest {
    private val property = "apple.awt.application.name"
    private val before: String? = System.getProperty(property)

    @AfterTest
    fun restore() {
        if (before == null) System.clearProperty(property) else System.setProperty(property, before)
    }

    @Test
    fun theMenuBarOfMacOSIsNamedAfterTheApplicationAndNotAfterItsMainClass() {
        nameApplication()

        assertEquals("KaTTY", System.getProperty(property))
        assertEquals("KaTTY", APP_NAME)
    }
}
