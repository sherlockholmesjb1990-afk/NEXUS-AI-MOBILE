package com.nexus.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class MockAIProvider : AIProvider, StreamingProvider {
    override suspend fun respond(messages: List<ChatMessage>, tools: List<NexusToolDefinition>, webSearch: Boolean): AgentResponse {
        val last = messages.lastOrNull { it.role == MessageRole.USER }?.text.orEmpty()
        return AgentResponse(
            text = "Modo demonstração NEXUS. Recebi: $last" + if (messages.any { it.image != null }) " Também recebi uma imagem." else ""
        )
    }

    override suspend fun continueWithToolResults(responseId: String, results: List<ToolResult>, tools: List<NexusToolDefinition>, webSearch: Boolean): AgentResponse =
        AgentResponse("Demonstração: ferramenta executada. " + results.joinToString { "${it.name}=${it.output}" })

    override fun streamText(messages: List<ChatMessage>, cycleId: String?): Flow<String> = flow {
        val text = "NEXUS em streaming. Recebi: ${messages.lastOrNull { it.role == MessageRole.USER }?.text.orEmpty()}"
        text.chunked(4).forEach { delay(25); emit(it) }
    }
}
