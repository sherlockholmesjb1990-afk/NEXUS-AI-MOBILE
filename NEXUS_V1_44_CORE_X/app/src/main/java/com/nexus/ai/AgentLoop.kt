package com.nexus.ai

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * V1.23: explicit, bounded Observe -> Decide -> Act -> Verify loop controller.
 * It coordinates phases and evidence but never executes tools or grants authority.
 */
class AgentLoop(
    private val maxIterations: Int,
    private val journal: AgentJournal? = null,
    private val taskId: String? = null,
    private val cycleId: String? = null
) {
    enum class Phase { OBSERVE, DECIDE, ACT, VERIFY, RECOVER, COMPLETE, BLOCKED, FAILED, CANCELLED }

    data class TraceEntry(
        val iteration: Int,
        val phase: Phase,
        val detail: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class Cycle(
        val iteration: Int,
        val phase: Phase,
        val observation: ObservationSnapshot? = null,
        val startedAt: Long = System.currentTimeMillis()
    )

    private val budget = maxIterations.coerceIn(1, 20)
    private var iteration = 0
    private var phase = Phase.OBSERVE
    private val trace = mutableListOf<TraceEntry>()

    fun hasNext(): Boolean = iteration < budget && phase !in setOf(Phase.COMPLETE, Phase.BLOCKED, Phase.FAILED, Phase.CANCELLED)

    suspend fun beginObservation(observation: ObservationSnapshot? = null): Cycle {
        currentCoroutineContext().ensureActive()
        check(hasNext()) { "Agent loop budget exhausted." }
        iteration += 1
        phase = Phase.OBSERVE
        val cycle = Cycle(iteration, phase, observation)
        record(Phase.OBSERVE, if (observation == null) "Iteração $iteration/$budget sem snapshot." else "Iteração $iteration/$budget com ${observation.observations.size} observação(ões).")
        return cycle
    }

    fun decide(detail: String = "") { transition(Phase.DECIDE, detail) }
    fun act(detail: String = "") { transition(Phase.ACT, detail) }
    fun verify(detail: String = "") { transition(Phase.VERIFY, detail) }
    fun recover(detail: String = "") { transition(Phase.RECOVER, detail) }
    fun observeAgain(detail: String = "") { transition(Phase.OBSERVE, detail) }
    fun complete(detail: String = "") { transition(Phase.COMPLETE, detail) }
    fun blocked(detail: String = "") { transition(Phase.BLOCKED, detail) }
    fun failed(detail: String = "") { transition(Phase.FAILED, detail) }
    fun cancelled(detail: String = "") { transition(Phase.CANCELLED, detail) }

    fun remainingIterations(): Int = (budget - iteration).coerceAtLeast(0)
    fun terminalReason(): String? = trace.lastOrNull { it.phase in setOf(Phase.COMPLETE, Phase.BLOCKED, Phase.FAILED, Phase.CANCELLED) }?.detail
    fun traceSnapshot(): List<TraceEntry> = trace.toList()

    private fun transition(next: Phase, detail: String) {
        val previous = phase
        phase = next
        record(next, "${previous.name} -> ${next.name}${if (detail.isBlank()) "" else ": ${detail.take(500)}"}")
    }

    private fun record(next: Phase, detail: String) {
        val entry = TraceEntry(iteration, next, detail.take(800))
        trace += entry
        if (trace.size > 100) trace.removeAt(0)
        taskId?.let { journal?.add(it, "AGENT_LOOP_${next.name}", entry.detail, cycleId) }
    }
}
