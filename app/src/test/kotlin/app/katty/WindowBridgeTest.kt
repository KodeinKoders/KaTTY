package app.katty

import app.katty.core.TerminalId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowBridgeTest {
    @Test
    fun aTerminalThatIsAskedForReachesTheMainWindow() = runBlocking {
        val bridge = WindowBridge()
        val received = async(start = CoroutineStart.UNDISPATCHED) { bridge.openTerminal.first() }

        assertTrue(bridge.openTerminal.tryEmit(TerminalId("t1")))

        assertEquals(TerminalId("t1"), received.await())
    }

    @Test
    fun theMainWindowIsToldToTakeTheFocusBackAndAskingNeverBlocks() = runBlocking {
        val bridge = WindowBridge()
        val received = async(start = CoroutineStart.UNDISPATCHED) { bridge.focusMain.first() }

        assertTrue(bridge.focusMain.tryEmit(Unit))
        assertEquals(Unit, received.await())
        // With nobody listening it is lost and does not block.
        repeat(5) { assertTrue(bridge.focusMain.tryEmit(Unit)) }
    }

    @Test
    fun askingWhenNobodyListensIsLostAndNeverBlocks() {
        val bridge = WindowBridge()

        repeat(20) { assertTrue(bridge.openTerminal.tryEmit(TerminalId("t$it"))) }
    }
}
