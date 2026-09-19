package com.nexus.ai

import java.util.concurrent.TimeUnit

enum class GoalKind { ACTION, RESEARCH, ORGANIZATION, MAINTENANCE, UNKNOWN }

data class GoalAssessment(
    val goalId: String,
    val kind: GoalKind,
    val priority: Int,
    val deadlineAt: Long?,
    val successCriteria: List<String>,
    val progress: GoalProgress,
    val nextTaskId: String?,
    val state: ObservationState
)

object GoalIntelligence {
    fun classify(objective: String): GoalKind {
        val text = objective.lowercase()
        return when {
            listOf("pesquis", "buscar", "encontr", "analis").any(text::contains) -> GoalKind.RESEARCH
            listOf("organ", "arrumar", "separar", "classific").any(text::contains) -> GoalKind.ORGANIZATION
            listOf("atualiz", "corrig", "manuten", "verific").any(text::contains) -> GoalKind.MAINTENANCE
            listOf("faça", "fazer", "crie", "criar", "execute", "abrir").any(text::contains) -> GoalKind.ACTION
            else -> GoalKind.UNKNOWN
        }
    }

    fun assessment(goal: NexusGoal, tasks: List<GoalTask>, progress: GoalProgress): GoalAssessment {
        val next = tasks.sortedBy { it.order }.firstOrNull { it.status == GoalTaskStatus.READY || it.status == GoalTaskStatus.PENDING }
        val state = when {
            goal.status == GoalStatus.COMPLETED -> ObservationState.COMPLETED
            goal.status == GoalStatus.BLOCKED -> ObservationState.BLOCKED
            goal.status == GoalStatus.FAILED -> ObservationState.UNKNOWN
            next != null -> ObservationState.ACTIVE
            else -> ObservationState.UNKNOWN
        }
        return GoalAssessment(goal.id, classify(goal.objective), goal.priority, goal.deadlineAt,
            goal.successCriteria, progress, next?.id, state)
    }

    fun reconcile(goal: NexusGoal, tasks: List<GoalTask>): List<NexusObservation> {
        val progress = GoalProgress(goal.id, tasks.size,
            tasks.count { it.status == GoalTaskStatus.COMPLETED },
            tasks.count { it.status == GoalTaskStatus.FAILED },
            if (tasks.isEmpty()) 0 else (tasks.count { it.status == GoalTaskStatus.COMPLETED } * 100 / tasks.size))
        val assessment = assessment(goal, tasks, progress)
        val result = mutableListOf<NexusObservation>()
        result += NexusObservation(kind = ObservationKind.GOAL, subjectId = goal.id, state = assessment.state,
            fact = "Objetivo '${goal.objective.take(180)}' estado=${goal.status.name}; progresso=${progress.percent}%.",
            confidence = if (goal.status == GoalStatus.COMPLETED) 1.0 else 0.9, source = "goal-state")
        tasks.forEach { task ->
            val state = when (task.status) {
                GoalTaskStatus.COMPLETED -> ObservationState.COMPLETED
                GoalTaskStatus.BLOCKED -> ObservationState.BLOCKED
                GoalTaskStatus.FAILED -> ObservationState.UNKNOWN
                GoalTaskStatus.RUNNING -> ObservationState.ACTIVE
                GoalTaskStatus.PENDING, GoalTaskStatus.READY -> ObservationState.PENDING
                GoalTaskStatus.CANCELLED -> ObservationState.UNKNOWN
            }
            result += NexusObservation(kind = ObservationKind.TASK, subjectId = task.id, state = state,
                fact = "Tarefa '${task.title.take(180)}' estado=${task.status.name}.",
                confidence = if (task.status == GoalTaskStatus.COMPLETED || task.status == GoalTaskStatus.BLOCKED) 1.0 else 0.85,
                source = "goal-task-state")
        }
        if (goal.deadlineAt != null) {
            val remaining = goal.deadlineAt - System.currentTimeMillis()
            val state = if (remaining <= 0) ObservationState.UNKNOWN else ObservationState.PENDING
            result += NexusObservation(kind = ObservationKind.GOAL, subjectId = goal.id, state = state,
                fact = if (remaining <= 0) "Prazo do objetivo expirado." else "Prazo restante aproximado: ${TimeUnit.MILLISECONDS.toHours(remaining)}h.",
                confidence = 1.0, source = "goal-deadline")
        }
        return result
    }
}
