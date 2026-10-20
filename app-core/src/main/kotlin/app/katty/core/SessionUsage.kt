package app.katty.core

/**
 * What a session of an agent has used.
 *
 * @param contextPercent How much of the model's context window the last answer filled, 0 to 100, or `null` when it is not known (no answer yet,
 * or a model whose window is not known).
 * @param costUsd What the session cost in dollars, with the sessions it started, or `null` when it is not known.
 */
public data class SessionUsage(val contextPercent: Int?, val costUsd: Double?)
