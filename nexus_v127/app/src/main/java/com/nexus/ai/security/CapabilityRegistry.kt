package com.nexus.ai.security

/** Explicit allowlist of capabilities available to the NEXUS runtime. */
class CapabilityRegistry(
    private val enabled: Set<CapabilityKind> = setOf(
        CapabilityKind.TOOL_EXECUTION,
        CapabilityKind.DEVICE_INFO,
        CapabilityKind.OPEN_EXTERNAL_APP,
        CapabilityKind.CLIPBOARD_WRITE
    )
) {
    fun isEnabled(capability: CapabilityKind): Boolean = capability in enabled
    fun all(): Set<CapabilityKind> = enabled
}
