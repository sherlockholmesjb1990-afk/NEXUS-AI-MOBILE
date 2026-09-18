package com.nexus.ai

/** V1.22 deterministic consistency checks for stale/conflicting/unknown evidence. */
object ObservationConsistency {
    fun isFresh(observation: NexusObservation, now: Long = System.currentTimeMillis()): Boolean =
        observation.expiresAt == null || observation.expiresAt > now

    fun normalize(observations: List<NexusObservation>, now: Long = System.currentTimeMillis()): List<NexusObservation> =
        ObservationReconciler.reconcile(observations.filter { isFresh(it, now) })

    fun conflictBecomesUnknown(a: NexusObservation, b: NexusObservation): Boolean {
        if (a.subjectId != b.subjectId || a.kind != b.kind || a.state == b.state) return false
        val result = ObservationReconciler.reconcile(listOf(a, b)).firstOrNull() ?: return false
        return result.state == ObservationState.UNKNOWN || result.source == "verified_tool_outcome"
    }
}
