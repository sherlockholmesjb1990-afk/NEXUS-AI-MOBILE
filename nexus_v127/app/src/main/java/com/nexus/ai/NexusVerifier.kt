package com.nexus.ai

import com.nexus.ai.security.CapabilityRegistry
import com.nexus.ai.security.NexusPolicyEngine
import com.nexus.ai.security.PolicyAction

class NexusVerifier(
    private val capabilities: CapabilityRegistry = CapabilityRegistry(),
    private val policy: NexusPolicyEngine = NexusPolicyEngine(capabilities)
) {
    data class Decision(
        val allowed: Boolean,
        val needsConfirmation: Boolean,
        val requiresVerification: Boolean,
        val reason: String,
        val ruleId: String,
        val riskScore: Int = 0,
        val riskBand: String = "LOW"
    )

    fun verify(tool: NexusToolDefinition, background: Boolean = false): Decision {
        val result = policy.evaluate(
            toolName = tool.name,
            capability = tool.capability,
            readOnly = tool.readOnly,
            background = background,
            permission = tool.permission
        )
        return Decision(
            allowed = result.action != PolicyAction.DENY,
            needsConfirmation = result.action == PolicyAction.REQUIRE_CONFIRMATION,
            requiresVerification = result.requiresVerification,
            reason = "${result.reason} risk=${result.risk.name}; rule=${result.ruleId}",
            ruleId = result.ruleId,
            riskScore = result.riskScore.value,
            riskBand = result.riskScore.band.name
        )
    }
}
