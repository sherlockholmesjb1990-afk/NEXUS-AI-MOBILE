package com.nexus.ai

enum class MessageRole { SYSTEM, USER, ASSISTANT, TOOL }

enum class TaskState {
    PLANNING, EXECUTING, VERIFYING, COMPLETED, BLOCKED, FAILED, CANCELLED
}

data class ImagePayload(
    val mime: String,
    val base64: String
)

data class ChatMessage(
    val role: MessageRole,
    val text: String,
    val image: ImagePayload? = null
)

data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

data class ToolResult(
    val callId: String,
    val name: String,
    val output: String,
    val success: Boolean
)

data class AgentResponse(
    val text: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val responseId: String? = null
)

data class AgentTask(
    val id: String,
    val objective: String,
    val state: TaskState,
    val createdAt: Long,
    val workId: String? = null
)

data class AgentEvent(
    val type: String,
    val detail: String,
    val timestamp: Long = System.currentTimeMillis()
)
