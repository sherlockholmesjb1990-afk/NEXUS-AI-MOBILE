package com.nexus.ai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class AgentJournal(context: Context) : SQLiteOpenHelper(context, "nexus_agent.db", null, 14) {
    override fun onCreate(db: SQLiteDatabase) {
        createTasks(db)
        createEvents(db)
        createAudits(db)
        createExecutionAudits(db)
        createPlans(db)
        createGoals(db)
        createRecoveryEvents(db)
        createPolicyAudits(db)
        createObservations(db)
        createContractEvents(db)
        createCatalogEvents(db)
        createActionResultEvents(db)
    }

    private fun createTasks(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS tasks (id TEXT PRIMARY KEY, objective TEXT NOT NULL, state TEXT NOT NULL, created_at INTEGER NOT NULL, work_id TEXT, last_response_id TEXT, checkpoint TEXT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_work_id ON tasks(work_id)")
    }

    private fun createEvents(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT NOT NULL, type TEXT NOT NULL, detail TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_task ON events(task_id)")
    }

    private fun createAudits(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS audits (id INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT, action TEXT NOT NULL, permission TEXT NOT NULL, decision TEXT NOT NULL, detail TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_audits_task ON audits(task_id)")
    }

    private fun createExecutionAudits(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS execution_audits (id INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT NOT NULL, call_id TEXT NOT NULL, tool TEXT NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_execution_audits_task ON execution_audits(task_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN work_id TEXT")
            db.execSQL("ALTER TABLE tasks ADD COLUMN last_response_id TEXT")
            db.execSQL("ALTER TABLE tasks ADD COLUMN checkpoint TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_work_id ON tasks(work_id)")
        }
        if (oldVersion < 3) createAudits(db)
        if (oldVersion < 4) createExecutionAudits(db)
        if (oldVersion < 5) createPlans(db)
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE plan_steps ADD COLUMN last_output TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 7) createGoals(db)
        if (oldVersion < 8) createRecoveryEvents(db)
        if (oldVersion < 9) createPolicyAudits(db)
        if (oldVersion < 10) {
            db.execSQL("ALTER TABLE goals ADD COLUMN priority INTEGER NOT NULL DEFAULT 50")
            db.execSQL("ALTER TABLE goals ADD COLUMN deadline_at INTEGER")
            db.execSQL("ALTER TABLE goals ADD COLUMN success_criteria TEXT NOT NULL DEFAULT ''")
            createObservations(db)
        }
        if (oldVersion < 11) createContractEvents(db)
        if (oldVersion < 12) createCatalogEvents(db)
        if (oldVersion < 13) createSelectionEvents(db)
        if (oldVersion < 14) createActionResultEvents(db)
    }

    private fun createContractEvents(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS contract_events (id INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT NOT NULL, action TEXT NOT NULL, contract_id TEXT NOT NULL, stage TEXT NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL, evidence TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_contract_events_task ON contract_events(task_id)")
    }

    private fun createCatalogEvents(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS catalog_events (id INTEGER PRIMARY KEY AUTOINCREMENT, action TEXT NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_catalog_events_action ON catalog_events(action)")
    }

    private fun createSelectionEvents(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS action_selection_events (id INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT, requested_action TEXT NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_action_selection_task ON action_selection_events(task_id)")
    }

    private fun createActionResultEvents(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS action_result_events (id INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT, action TEXT NOT NULL, state TEXT NOT NULL, detail TEXT NOT NULL, evidence TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_action_result_task ON action_result_events(task_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_action_result_action ON action_result_events(action)")
    }

    fun actionResultEvent(taskId: String?, outcome: AndroidActionResult) {
        writableDatabase.insert("action_result_events", null, ContentValues().apply {
            put("task_id", taskId); put("action", outcome.action); put("state", outcome.state.name)
            put("detail", outcome.detail); put("evidence", outcome.evidence.joinToString("\u001f")); put("created_at", outcome.normalizedAt)
        })
        add(taskId ?: "system", "ANDROID_RESULT_${outcome.state.name}", "${outcome.action}: ${outcome.detail}")
    }

    fun selectionEvent(taskId: String?, requestedAction: String, status: String, detail: String) {
        writableDatabase.insert("action_selection_events", null, ContentValues().apply {
            put("task_id", taskId); put("requested_action", requestedAction); put("status", status); put("detail", detail); put("created_at", System.currentTimeMillis())
        })
        add(taskId ?: "system", "ACTION_SELECTION_$status", "$requestedAction: $detail")
    }

    fun catalogEvent(action: String, validation: AndroidCatalogValidation) {
        writableDatabase.insert("catalog_events", null, ContentValues().apply {
            put("action", action); put("status", if (validation.valid) "VALID" else "REJECTED")
            put("detail", validation.issues.joinToString("; ").ifBlank { "Ação validada pelo catálogo seguro." })
            put("created_at", System.currentTimeMillis())
        })
        add("CATALOG", "CATALOG_${if (validation.valid) "VALID" else "REJECTED"}", "$action: ${validation.issues.joinToString("; ")}")
    }

    fun contractEvent(taskId: String, evaluation: ContractEvaluation) {
        writableDatabase.insert("contract_events", null, ContentValues().apply {
            put("task_id", taskId); put("action", evaluation.action); put("contract_id", evaluation.contractId)
            put("stage", evaluation.stage); put("status", evaluation.status.name); put("detail", evaluation.detail)
            put("evidence", evaluation.evidence.joinToString("\u001f")); put("created_at", evaluation.evaluatedAt)
        })
        add(taskId, "CONTRACT_${evaluation.stage}", "${evaluation.action}: ${evaluation.status} — ${evaluation.detail}")
    }

    private fun createPlans(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS plans (id TEXT PRIMARY KEY, task_id TEXT NOT NULL, objective TEXT NOT NULL, plan_json TEXT NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_plans_task ON plans(task_id)")
        db.execSQL("CREATE TABLE IF NOT EXISTS plan_steps (id TEXT PRIMARY KEY, plan_id TEXT NOT NULL, step_order INTEGER NOT NULL, tool_call_id TEXT NOT NULL, tool_name TEXT NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_plan_steps_plan ON plan_steps(plan_id)")
    }

    fun savePlan(taskId: String, plan: NexusPlan, json: String) {
        val now = System.currentTimeMillis()
        writableDatabase.insertWithOnConflict("plans", null, ContentValues().apply {
            put("id", plan.id); put("task_id", taskId); put("objective", plan.objective)
            put("plan_json", json); put("status", "CREATED"); put("created_at", now); put("updated_at", now)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        plan.steps.forEach { step ->
            writableDatabase.insertWithOnConflict("plan_steps", null, ContentValues().apply {
                put("id", step.id); put("plan_id", plan.id); put("step_order", step.order)
                put("tool_call_id", step.toolCallId); put("tool_name", step.toolName)
                put("status", "PENDING"); put("detail", step.expectedResult); put("last_output", ""); put("updated_at", now)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun updatePlanStep(stepId: String, status: String, detail: String, output: String? = null) {
        writableDatabase.update("plan_steps", ContentValues().apply {
            put("status", status); put("detail", detail)
            if (output != null) put("last_output", output)
            put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(stepId))
    }

    fun planStepState(stepId: String): Pair<String, String>? {
        readableDatabase.rawQuery("SELECT status, last_output FROM plan_steps WHERE id=?", arrayOf(stepId)).use { c ->
            return if (c.moveToFirst()) c.getString(0) to c.getString(1) else null
        }
    }

    data class PlanStepStateRecord(val stepId: String, val order: Int, val status: String, val detail: String, val lastOutput: String)

    fun planStepStates(planId: String): List<PlanStepStateRecord> {
        val out = mutableListOf<PlanStepStateRecord>()
        readableDatabase.rawQuery(
            "SELECT id,step_order,status,detail,last_output FROM plan_steps WHERE plan_id=? ORDER BY step_order",
            arrayOf(planId)
        ).use { c ->
            while (c.moveToNext()) out += PlanStepStateRecord(c.getString(0), c.getInt(1), c.getString(2), c.getString(3), c.getString(4))
        }
        return out
    }

    fun activePlan(taskId: String): Pair<String, String>? {
        readableDatabase.rawQuery("SELECT id, plan_json FROM plans WHERE task_id=? AND status IN ('CREATED','RUNNING') ORDER BY created_at DESC LIMIT 1", arrayOf(taskId)).use { c ->
            return if (c.moveToFirst()) c.getString(0) to c.getString(1) else null
        }
    }

    fun updatePlanStatus(planId: String, status: String) {
        writableDatabase.update("plans", ContentValues().apply {
            put("status", status); put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(planId))
    }
    private fun createRecoveryEvents(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS recovery_events (id INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT NOT NULL, tool TEXT NOT NULL, failure_type TEXT NOT NULL, action TEXT NOT NULL, attempt INTEGER NOT NULL, reason TEXT NOT NULL, fallback_tool TEXT, delay_ms INTEGER NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_recovery_task ON recovery_events(task_id)")
    }

    private fun createPolicyAudits(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS policy_audits (id INTEGER PRIMARY KEY AUTOINCREMENT, action TEXT NOT NULL, rule_id TEXT NOT NULL, success INTEGER NOT NULL, detail TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_policy_audits_rule ON policy_audits(rule_id)")
    }

    fun policyAudit(action: String, ruleId: String, success: Boolean, detail: String) {
        writableDatabase.insert("policy_audits", null, ContentValues().apply {
            put("action", action); put("rule_id", ruleId); put("success", if (success) 1 else 0); put("detail", detail); put("created_at", System.currentTimeMillis())
        })
        add("POLICY", "POLICY_${action}", "$ruleId: $detail")
    }

    fun recoveryEvent(taskId: String, tool: String, decision: RecoveryDecision) {
        writableDatabase.insert("recovery_events", null, ContentValues().apply {
            put("task_id", taskId); put("tool", tool); put("failure_type", decision.failureType.name)
            put("action", decision.action.name); put("attempt", decision.attempt); put("reason", decision.reason)
            put("fallback_tool", decision.fallbackTool); put("delay_ms", decision.delayMs); put("created_at", System.currentTimeMillis())
        })
        add(taskId, "RECOVERY_DECISION", "$tool: ${decision.action} — ${decision.reason}")
    }

    private fun createGoals(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS goals (id TEXT PRIMARY KEY, objective TEXT NOT NULL, status TEXT NOT NULL, priority INTEGER NOT NULL DEFAULT 50, deadline_at INTEGER, success_criteria TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS goal_tasks (id TEXT PRIMARY KEY, goal_id TEXT NOT NULL, title TEXT NOT NULL, task_order INTEGER NOT NULL, dependencies TEXT NOT NULL, status TEXT NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_goal_tasks_goal ON goal_tasks(goal_id)")
    }

    fun saveGoal(goal: NexusGoal) {
        writableDatabase.insertWithOnConflict("goals", null, ContentValues().apply {
            put("id", goal.id); put("objective", goal.objective); put("status", goal.status.name); put("priority", goal.priority); put("deadline_at", goal.deadlineAt); put("success_criteria", goal.successCriteria.joinToString("\u001f")); put("created_at", goal.createdAt)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateGoalStatus(goalId: String, status: GoalStatus) {
        writableDatabase.update("goals", ContentValues().apply { put("status", status.name) }, "id=?", arrayOf(goalId))
    }

    fun findGoalForObjective(objective: String): NexusGoal? {
        readableDatabase.rawQuery("SELECT id,objective,status,priority,deadline_at,success_criteria,created_at FROM goals WHERE objective=? ORDER BY created_at DESC LIMIT 1", arrayOf(objective.trim())).use { c ->
            return if (c.moveToFirst()) NexusGoal(c.getString(0), c.getString(1), GoalStatus.valueOf(c.getString(2)), c.getInt(3), if (c.isNull(4)) null else c.getLong(4), c.getString(5).split("\u001f").filter { it.isNotEmpty() }, c.getLong(6)) else null
        }
    }

    fun findGoal(goalId: String): NexusGoal? {
        readableDatabase.rawQuery("SELECT id,objective,status,priority,deadline_at,success_criteria,created_at FROM goals WHERE id=?", arrayOf(goalId)).use { c ->
            return if (c.moveToFirst()) NexusGoal(c.getString(0), c.getString(1), GoalStatus.valueOf(c.getString(2)), c.getInt(3), if (c.isNull(4)) null else c.getLong(4), c.getString(5).split("\u001f").filter { it.isNotEmpty() }, c.getLong(6)) else null
        }
    }

    fun saveGoalTask(task: GoalTask) {
        writableDatabase.insertWithOnConflict("goal_tasks", null, ContentValues().apply {
            put("id", task.id); put("goal_id", task.goalId); put("title", task.title); put("task_order", task.order)
            put("dependencies", task.dependencies.joinToString("\u001f")); put("status", task.status.name); put("updated_at", task.updatedAt)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateGoalTaskStatus(taskId: String, status: GoalTaskStatus) {
        writableDatabase.update("goal_tasks", ContentValues().apply { put("status", status.name); put("updated_at", System.currentTimeMillis()) }, "id=?", arrayOf(taskId))
    }

    fun goalTasks(goalId: String): List<GoalTask> {
        val out = mutableListOf<GoalTask>()
        readableDatabase.rawQuery("SELECT id,goal_id,title,task_order,dependencies,status,updated_at FROM goal_tasks WHERE goal_id=? ORDER BY task_order", arrayOf(goalId)).use { c ->
            while (c.moveToNext()) {
                val deps = c.getString(4).split("\u001f").filter { it.isNotEmpty() }
                out += GoalTask(c.getString(0), c.getString(1), c.getString(2), c.getInt(3), deps, GoalTaskStatus.valueOf(c.getString(5)), c.getLong(6))
            }
        }
        return out
    }

    fun createTask(taskId: String, objective: String, workId: String? = null) {
        writableDatabase.insertWithOnConflict("tasks", null, ContentValues().apply {
            put("id", taskId); put("objective", objective)
            put("state", TaskState.PLANNING.name); put("created_at", System.currentTimeMillis())
            put("work_id", workId)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun setTaskState(taskId: String, state: TaskState) {
        writableDatabase.update("tasks", ContentValues().apply { put("state", state.name) }, "id=?", arrayOf(taskId))
    }

    fun checkpointResponseId(taskId: String): String? {
        readableDatabase.rawQuery("SELECT last_response_id FROM tasks WHERE id=?", arrayOf(taskId)).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    fun checkpoint(taskId: String, responseId: String?, detail: String?) {
        writableDatabase.update("tasks", ContentValues().apply {
            put("last_response_id", responseId)
            put("checkpoint", detail)
        }, "id=?", arrayOf(taskId))
    }

    fun markCancelledByWorkId(workId: String) {
        writableDatabase.update("tasks", ContentValues().apply { put("state", TaskState.CANCELLED.name) }, "work_id=?", arrayOf(workId))
    }

    fun add(taskId: String, type: String, detail: String) {
        writableDatabase.insert("events", null, ContentValues().apply {
            put("task_id", taskId); put("type", type); put("detail", detail); put("created_at", System.currentTimeMillis())
        })
    }

    fun audit(taskId: String?, action: String, permission: com.nexus.ai.security.PermissionKind, decision: com.nexus.ai.security.PermissionDecision, detail: String) {
        writableDatabase.insert("audits", null, ContentValues().apply {
            put("task_id", taskId); put("action", action); put("permission", permission.name)
            put("decision", decision.name); put("detail", detail); put("created_at", System.currentTimeMillis())
        })
    }

    fun executionAudit(taskId: String, callId: String, tool: String, verification: VerificationResult) {
        writableDatabase.insert("execution_audits", null, ContentValues().apply {
            put("task_id", taskId); put("call_id", callId); put("tool", tool)
            put("status", verification.status.name); put("detail", verification.detail)
            put("created_at", System.currentTimeMillis())
        })
    }

    fun recent(limit: Int = 30): List<AgentEvent> {
        val out = mutableListOf<AgentEvent>()
        readableDatabase.rawQuery("SELECT type,detail,created_at FROM events ORDER BY id DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) out += AgentEvent(c.getString(0), c.getString(1), c.getLong(2))
        }
        return out
    }

    fun recentTasks(limit: Int = 20): List<AgentTask> {
        val out = mutableListOf<AgentTask>()
        readableDatabase.rawQuery("SELECT id,objective,state,created_at,work_id FROM tasks ORDER BY created_at DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) out += AgentTask(
                c.getString(0), c.getString(1), TaskState.valueOf(c.getString(2)), c.getLong(3), c.getString(4)
            )
        }
        return out
    }

    fun findTask(taskId: String): AgentTask? {
        readableDatabase.rawQuery("SELECT id,objective,state,created_at,work_id FROM tasks WHERE id=?", arrayOf(taskId)).use { c ->
            return if (c.moveToFirst()) AgentTask(c.getString(0), c.getString(1), TaskState.valueOf(c.getString(2)), c.getLong(3), c.getString(4)) else null
        }
    }

    fun clear() {
        writableDatabase.delete("execution_audits", null, null)
        writableDatabase.delete("policy_audits", null, null)
        writableDatabase.delete("plan_steps", null, null)
        writableDatabase.delete("plans", null, null)
        writableDatabase.delete("audits", null, null)
        writableDatabase.delete("events", null, null)
        writableDatabase.delete("goal_tasks", null, null)
        writableDatabase.delete("goals", null, null)
        writableDatabase.delete("observations", null, null)
        writableDatabase.delete("tasks", null, null)
    }
    private fun createObservations(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS observations (id TEXT PRIMARY KEY, kind TEXT NOT NULL, subject_id TEXT, state TEXT NOT NULL, fact TEXT NOT NULL, confidence REAL NOT NULL, source TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_observations_subject ON observations(subject_id, created_at DESC)")
    }

    fun saveObservation(observation: NexusObservation) {
        writableDatabase.insertWithOnConflict("observations", null, ContentValues().apply {
            put("id", observation.id); put("kind", observation.kind.name); put("subject_id", observation.subjectId)
            put("state", observation.state.name); put("fact", observation.fact.take(1000)); put("confidence", observation.confidence)
            put("source", observation.source.take(120)); put("created_at", observation.createdAt); put("expires_at", observation.expiresAt)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun observations(subjectId: String?, limit: Int): List<NexusObservation> {
        val out = mutableListOf<NexusObservation>()
        val sql = if (subjectId == null) "SELECT id,kind,subject_id,state,fact,confidence,source,created_at,expires_at FROM observations ORDER BY created_at DESC LIMIT ?" else "SELECT id,kind,subject_id,state,fact,confidence,source,created_at,expires_at FROM observations WHERE subject_id=? ORDER BY created_at DESC LIMIT ?"
        val args = if (subjectId == null) arrayOf(limit.toString()) else arrayOf(subjectId, limit.toString())
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) out += NexusObservation(c.getString(0), ObservationKind.valueOf(c.getString(1)), if (c.isNull(2)) null else c.getString(2), ObservationState.valueOf(c.getString(3)), c.getString(4), c.getDouble(5), c.getString(6), c.getLong(7), if (c.isNull(8)) null else c.getLong(8))
        }
        return out
    }

    fun trimObservations(subjectId: String?, max: Int) {
        if (subjectId == null) return
        writableDatabase.execSQL("DELETE FROM observations WHERE subject_id=? AND id NOT IN (SELECT id FROM observations WHERE subject_id=? ORDER BY created_at DESC LIMIT ?)", arrayOf(subjectId, subjectId, max))
    }

}
