package com.nexus.ai

/** V1.30: read-only diagnostic projection of the most recent agent/device cycle. */
data class DiagnosticActionResult(
    val action: String,
    val state: String,
    val detail: String,
    val createdAt: Long,
    val cycleId: String? = null
)

data class DiagnosticEvidence(
    val action: String,
    val source: String,
    val subjectId: String,
    val fact: String,
    val confidence: Double,
    val observedAt: Long,
    val cycleId: String? = null
)

data class DiagnosticContract(
    val action: String,
    val stage: String,
    val status: String,
    val detail: String,
    val createdAt: Long,
    val cycleId: String? = null
)

data class NexusDiagnosticSnapshot(
    val events: List<AgentEvent>,
    val actionResults: List<DiagnosticActionResult>,
    val evidence: List<DiagnosticEvidence>,
    val contracts: List<DiagnosticContract>
) {
    fun staleEvidence(now: Long = System.currentTimeMillis(), maxAgeMs: Long = 30_000L): List<DiagnosticEvidence> =
        evidence.filter { now - it.observedAt > maxAgeMs }

    fun cycleSummary(cycleId: String? = null): String {
        val scopedEvents = if (cycleId == null) events else events.filter { it.cycleId == cycleId }
        val stages = linkedSetOf<String>()
        scopedEvents.forEach { event ->
            when {
                event.type.contains("OBSERV") -> stages += "OBSERVE"
                event.type.contains("SELECT") || event.type.contains("PLAN") ||
                    event.type.contains("MODEL_RESPONSE") -> stages += "DECIDE"
                event.type.contains("ANDROID_RESULT") -> stages += "ACT"
                event.type.contains("ANDROID_EVIDENCE") -> stages += "EVIDENCE"
                event.type.contains("CONTRACT_POSTCONDITION") || event.type.contains("VERIF") -> stages += "VERIFY"
                event.type.contains("RECOVERY") -> stages += "RECOVER"
            }
        }
        return if (stages.isEmpty()) "Nenhum ciclo Android registrado ainda." else stages.joinToString(" → ")
    }
}


fun DiagnosticEvidence.ageLabel(now: Long = System.currentTimeMillis()): String {
    val seconds = ((now - observedAt).coerceAtLeast(0L)) / 1000L
    return if (seconds < 60) "${seconds}s atrás" else "${seconds / 60}min atrás"
}
