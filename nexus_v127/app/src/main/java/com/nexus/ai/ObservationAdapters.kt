package com.nexus.ai

import kotlin.math.max

/** V1.22: read-only observation adapters. Adapters may observe state but never execute tools or grant authority. */
interface ObservationAdapter {
    val source: String
    fun observe(subjectId: String? = null): List<NexusObservation>
}

data class ObservationSnapshot(
    val observations: List<NexusObservation>,
    val generatedAt: Long = System.currentTimeMillis()
) {
    /** Stable, bounded facts suitable for Context/Planner. */
    fun bounded(limit: Int = 20): List<NexusObservation> =
        observations
            .filter { it.expiresAt == null || it.expiresAt > generatedAt }
            .sortedWith(compareByDescending<NexusObservation> { it.confidence }.thenByDescending { it.createdAt })
            .take(limit.coerceIn(1, 50))
}

/** Converts persisted task state into an observation. */
class TaskObservationAdapter(private val journal: AgentJournal) : ObservationAdapter {
    override val source = "task_state"

    override fun observe(subjectId: String?): List<NexusObservation> {
        val task = subjectId?.let { journal.findTask(it) } ?: return emptyList()
        val state = when (task.state) {
            TaskState.COMPLETED -> ObservationState.COMPLETED
            TaskState.BLOCKED -> ObservationState.BLOCKED
            TaskState.FAILED -> ObservationState.UNKNOWN
            TaskState.CREATED, TaskState.PLANNING, TaskState.VERIFYING, TaskState.EXECUTING -> ObservationState.ACTIVE
        }
        return listOf(NexusObservation(
            kind = ObservationKind.TASK,
            subjectId = task.id,
            state = state,
            fact = "Tarefa persistida em estado ${task.state.name}.",
            confidence = 1.0,
            source = source
        ))
    }
}

/** Observes persisted plan/checkpoint state without touching execution. */
class PlanCheckpointObservationAdapter(private val journal: AgentJournal) : ObservationAdapter {
    override val source = "plan_checkpoint"

    override fun observe(subjectId: String?): List<NexusObservation> {
        val taskId = subjectId ?: return emptyList()
        val active = journal.activePlan(taskId) ?: return emptyList()
        val planId = active.first
        val states = journal.planStepStates(planId)
        if (states.isEmpty()) return emptyList()
        val observations = mutableListOf<NexusObservation>()
        states.forEach { item ->
            val state = when (item.status) {
                "CONFIRMED" -> ObservationState.COMPLETED
                "BLOCKED" -> ObservationState.BLOCKED
                "FAILED" -> ObservationState.UNKNOWN
                "RUNNING" -> ObservationState.ACTIVE
                else -> ObservationState.PENDING
            }
            observations += NexusObservation(
                kind = ObservationKind.CHECKPOINT,
                subjectId = item.stepId,
                state = state,
                fact = "Plano $planId etapa ${item.order}: ${item.status}.",
                confidence = if (item.status == "CONFIRMED") 1.0 else 0.9,
                source = source
            )
        }
        return observations
    }
}

/** Publishes an observation only from an explicit VerificationResult. */
class VerifiedToolOutcomeAdapter : ObservationAdapter {
    override val source = "verified_tool_outcome"

    override fun observe(subjectId: String?): List<NexusObservation> = emptyList()

    fun fromVerification(
        taskId: String?,
        toolName: String,
        result: ToolResult,
        verification: VerificationResult,
        now: Long = System.currentTimeMillis()
    ): NexusObservation {
        val state = when (verification.status) {
            VerificationStatus.CONFIRMED -> ObservationState.COMPLETED
            VerificationStatus.FAILED -> ObservationState.BLOCKED
            VerificationStatus.UNKNOWN -> ObservationState.UNKNOWN
        }
        val confidence = when (verification.status) {
            VerificationStatus.CONFIRMED, VerificationStatus.FAILED -> 1.0
            VerificationStatus.UNKNOWN -> 0.25
        }
        return NexusObservation(
            kind = ObservationKind.TOOL,
            subjectId = taskId,
            state = state,
            fact = "Ferramenta $toolName: ${verification.status.name}. ${verification.detail.take(500)}",
            confidence = confidence,
            source = source,
            createdAt = now
        )
    }
}

/**
 * Combines adapter evidence conservatively. Conflicting states are UNKNOWN unless
 * a trusted verified-tool observation establishes a CONFIRMED/FAILED outcome.
 */
object ObservationReconciler {
    fun reconcile(observations: List<NexusObservation>): List<NexusObservation> {
        val groups = observations.groupBy { it.subjectId to it.kind }
        return groups.values.map { group ->
            val fresh = group.filter { it.expiresAt == null || it.expiresAt > System.currentTimeMillis() }
            if (fresh.isEmpty()) return@map group.maxByOrNull { it.createdAt }!!
            val distinctStates = fresh.map { it.state }.distinct()
            val winner = when {
                distinctStates.size == 1 -> fresh.maxByOrNull { it.createdAt }!!
                fresh.any { it.source == "verified_tool_outcome" && it.state == ObservationState.COMPLETED } ->
                    fresh.first { it.source == "verified_tool_outcome" && it.state == ObservationState.COMPLETED }
                fresh.any { it.source == "verified_tool_outcome" && it.state == ObservationState.BLOCKED } ->
                    fresh.first { it.source == "verified_tool_outcome" && it.state == ObservationState.BLOCKED }
                else -> {
                    val newest = fresh.maxByOrNull { it.createdAt }!!
                    newest.copy(
                        state = ObservationState.UNKNOWN,
                        confidence = max(0.0, newest.confidence * 0.25),
                        fact = "Evidências conflitantes; estado preservado como UNKNOWN. ${newest.fact}"
                    )
                }
            }
            winner
        }.sortedByDescending { it.createdAt }
    }
}

class ObservationCoordinator(
    private val store: ObservationStore,
    private val adapters: List<ObservationAdapter>,
    private val verifiedToolAdapter: VerifiedToolOutcomeAdapter = VerifiedToolOutcomeAdapter()
) {
    fun snapshot(subjectId: String?, limit: Int = 20): ObservationSnapshot {
        val persisted = store.recent(subjectId, limit.coerceIn(1, 50))
        val fresh = adapters.flatMap { it.observe(subjectId) }
        return ObservationSnapshot(ObservationReconciler.reconcile(persisted + fresh)).bounded(limit)
    }

    fun recordVerifiedOutcome(
        taskId: String?,
        toolName: String,
        result: ToolResult,
        verification: VerificationResult
    ): NexusObservation = store.record(verifiedToolAdapter.fromVerification(taskId, toolName, result, verification))
}
