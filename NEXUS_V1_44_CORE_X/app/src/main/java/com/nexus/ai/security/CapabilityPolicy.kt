package com.nexus.ai.security

/** Central policy: a capability is never more permissive than its catalogue entry. */
object CapabilityPolicy {
    fun spec(capability: CapabilityKind): CapabilitySpec = CapabilityCatalog.spec(capability)
    fun requiresApproval(capability: CapabilityKind): Boolean = spec(capability).requiresApproval
    fun allowedInBackground(capability: CapabilityKind): Boolean = spec(capability).allowedInBackground
}
