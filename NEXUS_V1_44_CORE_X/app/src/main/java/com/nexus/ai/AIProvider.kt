package com.nexus.ai

interface AIProvider {
    suspend fun respond(
        messages: List<ChatMessage>,
        tools: List<NexusToolDefinition>,
        webSearch: Boolean = false
    ): AgentResponse

    suspend fun continueWithToolResults(
        responseId: String,
        results: List<ToolResult>,
        tools: List<NexusToolDefinition>,
        webSearch: Boolean = false
    ): AgentResponse
}
