package com.nexus.ai.security

enum class PermissionDecision {
    ALLOW,
    DENY,
    ASK
}

data class PermissionRequest(
    val id: String,
    val taskId: String?,
    val kind: PermissionKind,
    val title: String,
    val reason: String,
    val createdAt: Long = System.currentTimeMillis()
)
