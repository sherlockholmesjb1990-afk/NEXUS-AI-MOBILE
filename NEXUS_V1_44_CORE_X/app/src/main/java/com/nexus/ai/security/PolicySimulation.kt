package com.nexus.ai.security

/** V1.19: explainable policy simulation. Never executes a tool or changes permissions. */
data class PolicySimulationResult(
    val decision: PolicyDecision,
    val matchedRuleId: String?,
    val evaluatedRules: List<PolicyRule>,
    val ignoredRules: List<PolicyRule>,
    val riskScore: RiskScore = decision.riskScore
)

class PolicySimulation(private val engine: NexusPolicyEngine) {
    fun simulate(context: PolicyContext): PolicySimulationResult {
        val ordered = engine.rules().sortedByDescending { it.priority }
        val matches = ordered.filter { it.matches(context) }
        val winner = matches.firstOrNull()
        val decision = engine.evaluate(context)
        return PolicySimulationResult(decision, winner?.id, matches, ordered.filterNot { it in matches })
    }
}
