package com.nexus.ai.security

import android.content.Context

/** Persistent allowlist for capabilities. Unknown/absent grants are denied by default. */
class CapabilityGrantStore(context: Context) {
    private val prefs = context.getSharedPreferences("nexus_capability_grants", Context.MODE_PRIVATE)

    fun isGranted(capability: CapabilityKind): Boolean =
        prefs.getBoolean(capability.name, capability == CapabilityKind.TOOL_EXECUTION || capability == CapabilityKind.DEVICE_INFO)

    fun setGranted(capability: CapabilityKind, granted: Boolean) {
        prefs.edit().putBoolean(capability.name, granted).apply()
    }

    fun clear(capability: CapabilityKind) {
        prefs.edit().remove(capability.name).apply()
    }
}
