package com.nexus.ai

import java.util.UUID

/** V1.33: deterministic, privacy-conscious trace and execution context for one NEXUS cycle. */
data class NexusCycleContext(
    val cycleId: String,
    val objective: String
)

class NexusCycleTrace private constructor(
    val cycleId: String,
    val objective: String,
    private val clock: () -> Long
) {
    private val startedAt = clock()
    private val stages = mutableListOf<CycleStage>()

    /** Stable context propagated through the internal execution pipeline. */
    val context: NexusCycleContext get() = NexusCycleContext(cycleId, objective.take(500))

    fun stage(name: CycleStageName, detail: String = "") {
        stages += CycleStage(name, clock() - startedAt, detail.take(500))
    }

    fun finish(result: CycleResult, detail: String = ""): CycleTraceReport =
        CycleTraceReport(cycleId, objective.take(500), startedAt, clock(), stages.toList(), result, detail.take(500))

    companion object {
        fun begin(objective: String, clock: () -> Long = System::currentTimeMillis): NexusCycleTrace =
            NexusCycleTrace("cycle-${UUID.randomUUID()}", objective, clock)
    }
}

enum class CycleStageName { OBSERVE, DECIDE, POLICY, ACT, EVIDENCE, VERIFY, RECOVER, COMPLETE }
enum class CycleResult { SUCCESS, FAILED, UNKNOWN, BLOCKED }

data class CycleStage(val name: CycleStageName, val elapsedMs: Long, val detail: String)

data class CycleTraceReport(
    val cycleId: String,
    val objective: String,
    val startedAt: Long,
    val finishedAt: Long,
    val stages: List<CycleStage>,
    val result: CycleResult,
    val detail: String
) {
    val durationMs: Long get() = (finishedAt - startedAt).coerceAtLeast(0L)

    fun compact(): String = buildString {
        append(cycleId).append(" | ")
        append(stages.joinToString(" → ") { it.name.name })
        append(" | ").append(result.name)
        append(" | ").append(durationMs).append("ms")
    }
}
