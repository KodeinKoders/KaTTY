package app.katty.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenSessionsTest {
    private var counter = 0
    private val store = KattyStore(newId = { "id${counter++}" })

    @Test
    fun addsUpTheSessionsOfTheTerminalsThatAreOpen() {
        store.addProject("/a")
        val first = store.state.value.selection.terminal!!
        val second = store.split(first, SplitDirection.ROW)!!
        val usages = mapOf(first to SessionUsage(10, 1.5), second to SessionUsage(null, 2.0), TerminalId("gone") to SessionUsage(5, 100.0))

        assertEquals(OpenSessions(2, 3.5), store.state.value.openSessionsCost(usages))
    }

    @Test
    fun noCostIsKnownWhenNoSessionSaysIt() {
        store.addProject("/a")
        val id = store.state.value.selection.terminal!!

        assertEquals(OpenSessions(1, null), store.state.value.openSessionsCost(mapOf(id to SessionUsage(40, null))))
    }

    @Test
    fun noSessionIsNothingToSay() {
        store.addProject("/a")

        assertNull(store.state.value.openSessionsCost(emptyMap()))
    }
}
