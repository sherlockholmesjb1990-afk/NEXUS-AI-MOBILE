package com.nexus.ai.security

/**
 * V1.17: central policy decision point.
 * Policy only decides what is required; it never grants Android permissions and never executes tools.
 */
enum class PolicyAction {
    ALLOW,
    DENY,
    REQUIRE_CONFIRMATION,
    REQUIRE_VERIFICATION
}

data class PolicyContext(
    val toolName: String,
    val capability: CapabilityKind,
    val readOnly: Boolean,
    val background: Boolean,
    val permission: PermissionKind,
    val requestOrigin: RequestOrigin = RequestOrigin.AGENT,
    val taskType: String? = null,
    val verificationConfidence: Double? = null,
    val userState: UserState = UserState.UNKNOWN
)

data class PolicyDecision(
    val action: PolicyAction,
    val reason: String,
    val risk: RiskLevel,
    val ruleId: String,
    val riskScore: RiskScore = RiskScore(0, RiskBand.LOW, emptyList())
) {
    val allowed: Boolean get() = action != PolicyAction.DENY
    val needsConfirmation: Boolean get() = action == PolicyAction.REQUIRE_CONFIRMATION
    val requiresVerification: Boolean get() = action == PolicyAction.REQUIRE_VERIFICATION || action == PolicyAction.REQUIRE_CONFIRMATION
}

data class PolicyRule(
    val id: String,
    val description: String,
    val priority: Int,
    val matches: (PolicyContext) -> Boolean,
    val action: PolicyAction,
    val reason: String
)

class NexusPolicyEngine(
    private val registry: CapabilityRegistry = CapabilityRegistry(),
    private val rules: List<PolicyRule>? = null,
    private val repository: PolicyRepository? = null,
    private val riskConfig: RiskScoringConfig = RiskScoringConfig()
) {
    fun evaluate(context: PolicyContext): PolicyDecision {
        val spec = CapabilityCatalog.spec(context.capability)
        val score = RiskScorer.score(context, riskConfig)
        if (!registry.isEnabled(context.capability)) {
            return PolicyDecision(PolicyAction.DENY, "Capability ${context.capability.name} não está habilitada.", spec.risk, "capability-disabled", score)
        }

        if (context.background && !spec.allowedInBackground) {
            return PolicyDecision(PolicyAction.DENY, "Capability ${context.capability.name} não é permitida em background.", spec.risk, "background-denied", score)
        }

        val effectiveRules = repository?.rules() ?: rules ?: defaultRules()
        val rule = effectiveRules.sortedByDescending { it.priority }.firstOrNull { it.matches(context) }
            ?: return PolicyDecision(PolicyAction.DENY, "Nenhuma regra de segurança autorizou a ação.", spec.risk, "default-deny", score)

        val restrictedAction = restrictByScore(rule.action, score, context, rule)
        val scoreNote = "riskScore=${score.value} (${score.band.name}); fatores=${score.explain()}"
        return PolicyDecision(restrictedAction, "${rule.reason} $scoreNote", spec.risk, rule.id, score)
    }

    private fun restrictByScore(action: PolicyAction, score: RiskScore, context: PolicyContext, rule: PolicyRule): PolicyAction {
        if (rule.id.startsWith("builtin-") && action == PolicyAction.DENY) return action
        return when (score.band) {
            RiskBand.CRITICAL -> if (action == PolicyAction.ALLOW || action == PolicyAction.REQUIRE_VERIFICATION) PolicyAction.REQUIRE_CONFIRMATION else action
            RiskBand.HIGH -> if (action == PolicyAction.ALLOW) PolicyAction.REQUIRE_CONFIRMATION else action
            RiskBand.MEDIUM -> if (action == PolicyAction.ALLOW && !context.readOnly) PolicyAction.REQUIRE_VERIFICATION else action
            RiskBand.LOW -> action
        }
    }

    fun evaluate(
        toolName: String,
        capability: CapabilityKind,
        readOnly: Boolean,
        background: Boolean,
        permission: PermissionKind
    ): PolicyDecision = evaluate(PolicyContext(toolName, capability, readOnly, background, permission))

    fun rules(): List<PolicyRule> = (repository?.rules() ?: rules ?: defaultRules()).sortedByDescending { it.priority }

    companion object {
        fun defaultRules(): List<PolicyRule> = PolicyRepository.builtinRecords().map { record ->
            PolicyRule(
                record.id, record.description, record.priority,
                when (record.matcherType) {
                    PolicyMatchType.PERMISSION -> { ctx -> ctx.permission.name == record.matcherValue }
                    PolicyMatchType.RISK_AT_LEAST -> { ctx -> CapabilityCatalog.spec(ctx.capability).risk >= RiskLevel.valueOf(record.matcherValue ?: RiskLevel.HIGH.name) }
                    PolicyMatchType.MUTATING -> { ctx -> !ctx.readOnly }
                    PolicyMatchType.BACKGROUND_MUTATING -> { ctx -> ctx.background && !ctx.readOnly }
                    PolicyMatchType.READ_ONLY_LOW -> { ctx -> ctx.readOnly && CapabilityCatalog.spec(ctx.capability).risk == RiskLevel.LOW }
                    PolicyMatchType.TOOL -> { ctx -> ctx.toolName == record.matcherValue }
                    PolicyMatchType.ANY -> { _ -> true }
                },
                record.action, record.reason
            )
        }
    }
}
