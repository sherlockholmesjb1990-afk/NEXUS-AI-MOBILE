package com.nexus.ai

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.util.UUID

/** V1.15: persistent high-level goal management. Does not execute tools or grant permissions. */
class GoalManager(private val journal: AgentJournal) {
    fun createGoal(objective: String, priority: Int = 50, deadlineAt: Long? = null, successCriteria: List<String> = emptyList()): NexusGoal {
        require(objective.isNotBlank()) { "Objetivo não pode ser vazio." }
        val goal = NexusGoal(UUID.randomUUID().toString(), objective.trim(), GoalStatus.PENDING, priority.coerceIn(1, 100), deadlineAt, successCriteria.filter { it.isNotBlank() }.take(10))
        journal.saveGoal(goal)
        journal.add(goal.id, "GOAL_CREATED", "Objetivo criado: ${goal.objective}")
        return goal
    }

    fun addTask(goalId: String, title: String, order: Int, dependencies: List<String> = emptyList()): GoalTask {
        require(title.isNotBlank()) { "Título da tarefa não pode ser vazio." }
        val task = GoalTask(UUID.randomUUID().toString(), goalId, title.trim(), order, dependencies, GoalTaskStatus.PENDING)
        journal.saveGoalTask(task)
        return task
    }

    fun setGoalStatus(goalId: String, status: GoalStatus) = journal.updateGoalStatus(goalId, status)
    fun assess(goalId: String): GoalAssessment? {
        val goal = getGoal(goalId) ?: return null
        val tasks = tasks(goalId)
        return GoalIntelligence.assessment(goal, tasks, progress(goalId))
    }
    fun reconcile(goalId: String, observations: ObservationStore): List<NexusObservation>? {
        val goal = getGoal(goalId) ?: return null
        return observations.reconcileGoal(goal, tasks(goalId))
    }
    fun setTaskStatus(taskId: String, status: GoalTaskStatus) = journal.updateGoalTaskStatus(taskId, status)

    fun getGoal(goalId: String): NexusGoal? = journal.findGoal(goalId)
    fun tasks(goalId: String): List<GoalTask> = journal.goalTasks(goalId)

    fun progress(goalId: String): GoalProgress {
        val tasks = tasks(goalId)
        val completed = tasks.count { it.status == GoalTaskStatus.COMPLETED }
        val failed = tasks.count { it.status == GoalTaskStatus.FAILED }
        val percent = if (tasks.isEmpty()) 0 else ((completed * 100.0) / tasks.size).toInt()
        return GoalProgress(goalId, tasks.size, completed, failed, percent)
    }

    /** Conservative decomposition: creates a goal and task skeleton from supplied titles. */
    fun createFromTaskTitles(objective: String, titles: List<String>): NexusGoal {
        val goal = createGoal(objective)
        titles.filter { it.isNotBlank() }.forEachIndexed { index, title ->
            addTask(goal.id, title, index + 1, if (index == 0) emptyList() else listOf("order:${index}"))
        }
        return goal
    }
}

enum class GoalStatus { PENDING, ACTIVE, BLOCKED, COMPLETED, FAILED, CANCELLED }
enum class GoalTaskStatus { PENDING, READY, RUNNING, COMPLETED, BLOCKED, FAILED, CANCELLED }

data class NexusGoal(val id: String, val objective: String, val status: GoalStatus, val priority: Int = 50, val deadlineAt: Long? = null, val successCriteria: List<String> = emptyList(), val createdAt: Long = System.currentTimeMillis())
data class GoalTask(val id: String, val goalId: String, val title: String, val order: Int, val dependencies: List<String>, val status: GoalTaskStatus, val updatedAt: Long = System.currentTimeMillis())
data class GoalProgress(val goalId: String, val total: Int, val completed: Int, val failed: Int, val percent: Int)
