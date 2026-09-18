package com.nexus.ai.security

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CompletableDeferred

/** Human-in-the-loop coordinator. A pending request suspends the agent until the UI decides. */
class PermissionCenter(private val store: PermissionStore) {
    val pending: MutableState<PermissionRequest?> = mutableStateOf(null)
    private var pendingDecision: CompletableDeferred<PermissionDecision>? = null

    fun request(taskId: String?, kind: PermissionKind, title: String, reason: String): PermissionGate.Result {
        val gate = PermissionGate(store)
        return when (val result = gate.check(taskId, kind, title, reason)) {
            PermissionGate.Result.Allowed -> result
            is PermissionGate.Result.Denied -> result
            is PermissionGate.Result.NeedsApproval -> { pending.value = result.request; result }
        }
    }

    suspend fun await(taskId: String?, kind: PermissionKind, title: String, reason: String): PermissionGate.Result {
        val deferred = CompletableDeferred<PermissionDecision>()
        pendingDecision = deferred
        when (val result = request(taskId, kind, title, reason)) {
            PermissionGate.Result.Allowed, is PermissionGate.Result.Denied -> {
                pendingDecision = null
                return result
            }
            is PermissionGate.Result.NeedsApproval -> Unit
        }
        val decision = deferred.await()
        return when (decision) {
            PermissionDecision.ALLOW -> PermissionGate.Result.Allowed
            PermissionDecision.DENY -> PermissionGate.Result.Denied("Permissão ${kind.name} negada pelo usuário.")
            PermissionDecision.ASK -> PermissionGate.Result.Denied("Permissão ${kind.name} não foi autorizada.")
        }
    }

    fun decide(decision: PermissionDecision) {
        pending.value?.let { store.setDecision(it.kind, decision) }
        pending.value = null
        pendingDecision?.let { if (!it.isCompleted) it.complete(decision) }
        pendingDecision = null
    }
}
