package com.nexus.ai.security

import android.content.Context
import java.util.UUID

class PermissionStore(context: Context) {
    private val prefs = context.getSharedPreferences("nexus_permissions", Context.MODE_PRIVATE)

    fun decision(kind: PermissionKind): PermissionDecision {
        return when (prefs.getString(kind.name, null)) {
            "ALLOW" -> PermissionDecision.ALLOW
            "DENY" -> PermissionDecision.DENY
            else -> PermissionDecision.ASK
        }
    }

    fun setDecision(kind: PermissionKind, decision: PermissionDecision) {
        prefs.edit().putString(kind.name, decision.name).apply()
    }

    fun request(taskId: String?, kind: PermissionKind, title: String, reason: String): PermissionRequest {
        return PermissionRequest(
            id = UUID.randomUUID().toString(),
            taskId = taskId,
            kind = kind,
            title = title,
            reason = reason
        )
    }
}
