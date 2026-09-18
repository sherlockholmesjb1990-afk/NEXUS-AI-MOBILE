package com.nexus.ai.security

class PermissionGate(private val store: PermissionStore) {
    sealed class Result {
        data object Allowed : Result()
        data class Denied(val reason: String) : Result()
        data class NeedsApproval(val request: PermissionRequest) : Result()
    }

    fun check(taskId: String?, kind: PermissionKind, title: String, reason: String): Result {
        return when (store.decision(kind)) {
            PermissionDecision.ALLOW -> Result.Allowed
            PermissionDecision.DENY -> Result.Denied("Permissão ${kind.name} negada.")
            PermissionDecision.ASK -> Result.NeedsApproval(
                store.request(taskId, kind, title, reason)
            )
        }
    }
}
