package app.katty

import app.katty.core.AgentInfo
import app.katty.core.AgentReport
import app.katty.core.AgentState
import app.katty.core.TerminalId
import app.katty.terminals.AgentTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttentionSoundTest {
    private var time = 0L
    private var played = 0
    private val sound = AttentionSound(clock = { time }, sound = { played++ })

    private fun transition(from: AgentState, to: AgentState) = AgentTransition(TerminalId("t"), from, AgentInfo(AgentReport(to, app = "claude"), since = 0))

    @Test
    fun agentsThatNeedTheUserAtTheSameMomentMakeOneSound() {
        sound.play()
        time += 400_000_000L
        sound.play()
        time += 400_000_000L
        sound.play()

        assertEquals(1, played)
    }

    @Test
    fun anAgentThatNeedsTheUserLaterMakesASoundAgain() {
        sound.play()
        time += 1_000_000_000L
        sound.play()

        assertEquals(2, played)
    }

    @Test
    fun anAgentThatStartsWaitingOrFailsStartsNeedingTheUser() {
        assertTrue(transition(AgentState.IDLE, AgentState.NEEDS_INPUT).startsNeedingTheUser())
        assertTrue(transition(AgentState.WORKING, AgentState.NEEDS_INPUT).startsNeedingTheUser())
        assertTrue(transition(AgentState.DONE, AgentState.NEEDS_INPUT).startsNeedingTheUser())
        assertTrue(transition(AgentState.WORKING, AgentState.ERROR).startsNeedingTheUser())
    }

    @Test
    fun anAgentThatFinishesOrGoesBackToWorkDoesNotStartNeedingTheUser() {
        assertFalse(transition(AgentState.WORKING, AgentState.DONE).startsNeedingTheUser())
        assertFalse(transition(AgentState.NEEDS_INPUT, AgentState.WORKING).startsNeedingTheUser())
        assertFalse(transition(AgentState.DONE, AgentState.IDLE).startsNeedingTheUser())
    }

    // It already needed the user: the sound was made when it started waiting.
    @Test
    fun aWaitThatBecomesAFailureDoesNotStartNeedingTheUserAgain() {
        assertFalse(transition(AgentState.NEEDS_INPUT, AgentState.ERROR).startsNeedingTheUser())
        assertFalse(transition(AgentState.ERROR, AgentState.NEEDS_INPUT).startsNeedingTheUser())
    }
}
