package app.katty.core

/**
 * What an agent in a terminal is doing, as the application shows it (the words of the design in brackets).
 *
 * It is derived from what the program reports of itself (OSC 7501): see `AgentStates`.
 */
public enum class AgentState {
    /** Nothing: a plain shell, or an agent at rest. */
    IDLE,

    /** Working: the agent runs. */
    WORKING,

    /** Needs input: the agent waits for the user. */
    NEEDS_INPUT,

    /** Done: the agent finished. */
    DONE,

    /** Failed and stopped. It is shown as needing the user, with its own glyph. */
    ERROR,
    ;

    /** How urgent it is for the user, the highest first: what a project or a rail shows is the most urgent state of what it holds. */
    public val urgency: Int
        get() = when (this) {
            NEEDS_INPUT -> 4
            ERROR -> 3
            WORKING -> 2
            DONE -> 1
            IDLE -> 0
        }
}
