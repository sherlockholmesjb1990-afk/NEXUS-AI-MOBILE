package com.nexus.ai

import com.nexus.ai.security.CapabilityPolicy
import org.json.JSONObject
import java.util.UUID

/**
 * Planner V1.12: transforma as tool calls produzidas pelo modelo em um plano
 * explícito e auditável antes que qualquer ferramenta seja executada.
 * O planner é determinístico e não concede permissões por conta própria.
 */
class NexusPlanner {
    /** V1.27: planner may validate Android requests against the safe catalog, but never grants authority. */
    fun validateAndroidSelections(calls: List<ToolCall>, selector: AndroidActionSelector?, taskId: String? = null): List<String> {
        if (selector == null) return emptyList()
        return calls.filter { selector.select(it.name, taskId).status == AndroidActionSelector.Status.REJECTED }
            .map { it.name }
    }

    fun createPlan(objective: String, calls: List<ToolCall>, registry: ToolRegistry): NexusPlan {
        val stepIds = calls.map { "step-${UUID.randomUUID().toString().take(8)}" }
        val steps = calls.mapIndexed { index, call ->
            val definition = registry.get(call.name)?.definition
            val capability = definition?.capability ?: com.nexus.ai.security.CapabilityKind.TOOL_EXECUTION
            val risk = CapabilityPolicy.spec(capability).risk.name
            PlanStep(
                id = stepIds[index],
                order = index + 1,
                toolCallId = call.id,
                toolName = call.name,
                argumentsJson = call.argumentsJson,
                dependencies = if (index == 0) emptyList() else listOf(stepIds[index - 1]),
                preconditions = listOf(
                    "tool_registered",
                    "capability_enabled",
                    "verifier_allows",
                    "permission_allows"
                ),
                expectedResult = "Ferramenta ${call.name} deve retornar resultado verificável.",
                risk = risk,
                checkpoint = true
            )
        }
        return NexusPlan(
            id = UUID.randomUUID().toString(),
            objective = objective,
            steps = steps
        )
    }


    fun fromJson(json: String): NexusPlan {
        val root = JSONObject(json)
        val stepsJson = root.optJSONArray("steps") ?: org.json.JSONArray()
        val steps = buildList {
            for (i in 0 until stepsJson.length()) {
                val o = stepsJson.getJSONObject(i)
                val deps = mutableListOf<String>()
                val depJson = o.optJSONArray("dependencies") ?: org.json.JSONArray()
                for (j in 0 until depJson.length()) deps += depJson.getString(j)
                val pre = mutableListOf<String>()
                val preJson = o.optJSONArray("preconditions") ?: org.json.JSONArray()
                for (j in 0 until preJson.length()) pre += preJson.getString(j)
                add(PlanStep(o.getString("id"), o.getInt("order"), o.getString("toolCallId"), o.getString("toolName"), o.getString("argumentsJson"), deps, pre, o.getString("expectedResult"), o.getString("risk"), o.optBoolean("checkpoint", true)))
            }
        }
        return NexusPlan(root.getString("id"), root.getString("objective"), steps, root.optLong("createdAt", System.currentTimeMillis()))
    }

    fun toJson(plan: NexusPlan): String = JSONObject().apply {
        put("id", plan.id)
        put("objective", plan.objective)
        put("createdAt", plan.createdAt)
        put("steps", org.json.JSONArray().apply {
            plan.steps.forEach { step ->
                put(JSONObject().apply {
                    put("id", step.id)
                    put("order", step.order)
                    put("toolCallId", step.toolCallId)
                    put("toolName", step.toolName)
                    put("argumentsJson", step.argumentsJson)
                    put("dependencies", org.json.JSONArray().apply { step.dependencies.forEach { put(it) } })
                    put("preconditions", org.json.JSONArray().apply { step.preconditions.forEach { put(it) } })
                    put("expectedResult", step.expectedResult)
                    put("risk", step.risk)
                    put("checkpoint", step.checkpoint)
                })
            }
        })
    }.toString()
}

data class NexusPlan(
    val id: String,
    val objective: String,
    val steps: List<PlanStep>,
    val createdAt: Long = System.currentTimeMillis()
)

data class PlanStep(
    val id: String,
    val order: Int,
    val toolCallId: String,
    val toolName: String,
    val argumentsJson: String,
    val dependencies: List<String>,
    val preconditions: List<String>,
    val expectedResult: String,
    val risk: String,
    val checkpoint: Boolean
)
