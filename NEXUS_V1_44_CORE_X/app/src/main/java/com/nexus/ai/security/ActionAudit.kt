package com.nexus.ai.security

data class ActionAudit(
    val taskId: String?,
    val action: String,
    val permission: PermissionKind,
    val decision: PermissionDecision,
    val detail: String,
    val timestamp: Long = System.currentTimeMillis()
)
