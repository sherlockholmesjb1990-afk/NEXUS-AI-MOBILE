package com.nexus.ai

/**
 * Immutable continuation anchor used by V1.42 tests and recovery logic.
 * A resume is valid only when task and cycle identity both match.
 */
data class NexusResumeAnchor(
    val taskId: String,
    val cycleId: String,
    val responseId: String?,
    val checkpoint: String?
) {
    fun matches(taskId: String, cycleId: String): Boolean =
        this.taskId == taskId && this.cycleId == cycleId
}
