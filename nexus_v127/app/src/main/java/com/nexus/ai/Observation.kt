package com.nexus.ai

import org.json.JSONObject
import java.util.UUID

enum class ObservationKind { TASK, GOAL, TOOL, CHECKPOINT, CONTEXT, ANDROID_STATE }
enum class ObservationState { COMPLETED, PENDING, BLOCKED, UNKNOWN, ACTIVE }

data class NexusObservation(
    val id: String = UUID.randomUUID().toString(),
    val kind: ObservationKind,
    val subjectId: String?,
    val state: ObservationState,
    val fact: String,
    val confidence: Double,
    val source: String,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long? = null
) {
    init {
        require(confidence in 0.0..1.0)
        require(fact.isNotBlank())
    }
    fun toJson(): String = JSONObject().apply {
        put("id", id); put("kind", kind.name); put("subjectId", subjectId)
        put("state", state.name); put("fact", fact.take(1000)); put("confidence", confidence)
        put("source", source.take(120)); put("createdAt", createdAt); put("expiresAt", expiresAt)
    }.toString()
}

class ObservationStore(private val journal: AgentJournal, private val maxPerSubject: Int = 30) {
    fun record(observation: NexusObservation): NexusObservation {
        journal.saveObservation(observation)
        journal.trimObservations(observation.subjectId, maxPerSubject)
        return observation
    }
    fun recent(subjectId: String? = null, limit: Int = 12): List<NexusObservation> =
        journal.observations(subjectId, limit.coerceIn(1, 50))
            .filter { it.expiresAt == null || it.expiresAt > System.currentTimeMillis() }

    fun reconcileGoal(goal: NexusGoal, tasks: List<GoalTask>): List<NexusObservation> =
        GoalIntelligence.reconcile(goal, tasks).map { record(it) }
}
