package com.nexus.ai.security

/** Risk levels used by the NEXUS capability policy. */
enum class RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

data class CapabilitySpec(
    val kind: CapabilityKind,
    val risk: RiskLevel,
    val requiresApproval: Boolean,
    val allowedInBackground: Boolean,
    val androidPermission: String? = null
)

/** Central capability catalogue: capabilities are explicit and least-privilege by default. */
object CapabilityCatalog {
    private val specs = mapOf(
        CapabilityKind.TOOL_EXECUTION to CapabilitySpec(CapabilityKind.TOOL_EXECUTION, RiskLevel.LOW, false, true),
        CapabilityKind.DEVICE_INFO to CapabilitySpec(CapabilityKind.DEVICE_INFO, RiskLevel.LOW, false, true),
        CapabilityKind.OPEN_EXTERNAL_APP to CapabilitySpec(CapabilityKind.OPEN_EXTERNAL_APP, RiskLevel.MEDIUM, true, false),
        CapabilityKind.CLIPBOARD_WRITE to CapabilitySpec(CapabilityKind.CLIPBOARD_WRITE, RiskLevel.MEDIUM, true, false)
    )

    fun spec(kind: CapabilityKind): CapabilitySpec = specs[kind]
        ?: CapabilitySpec(kind, RiskLevel.HIGH, true, false)
}

/** A tool gets only the capability explicitly bound to its definition. */
data class CapabilityGrant(
    val capability: CapabilityKind,
    val source: String,
    val grantedAt: Long = System.currentTimeMillis()
)

class CapabilitySandbox(private val registry: CapabilityRegistry) {
    fun inspect(tool: NexusToolAccess): SandboxDecision {
        val capability = tool.capability
        if (!registry.isEnabled(capability)) {
            return SandboxDecision(false, false, "Capability ${capability.name} não está habilitada.")
        }
        val spec = CapabilityCatalog.spec(capability)
        return SandboxDecision(true, spec.requiresApproval, "${capability.name}: risco=${spec.risk.name}; background=${spec.allowedInBackground}.")
    }
}

data class SandboxDecision(
    val allowed: Boolean,
    val needsApproval: Boolean,
    val reason: String
)

interface NexusToolAccess {
    val capability: CapabilityKind
}
