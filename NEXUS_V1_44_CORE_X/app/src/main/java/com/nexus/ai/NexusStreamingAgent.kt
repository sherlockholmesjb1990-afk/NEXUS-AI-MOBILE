package com.nexus.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed interface AgentStreamEvent {
    data class Status(val state: TaskState): AgentStreamEvent
    data class TextDelta(val text: String): AgentStreamEvent
    data class Tool(val name: String): AgentStreamEvent
    data class Finished(val response: AgentResponse): AgentStreamEvent
    data class Error(val message: String): AgentStreamEvent
}

/*
 * Provider-neutral streaming facade.
 * V1.2 keeps the agent execution bounded while exposing a Flow to the UI.
 * Remote providers can replace the simple final-text emission with true
 * server streaming without changing the UI contract.
 */
class NexusStreamingAgent(private val agent: NexusAgent) {
    fun run(task: suspend () -> AgentResponse): Flow<AgentStreamEvent> = flow {
        try {
            emit(AgentStreamEvent.Status(TaskState.EXECUTING))
            val result = task()
            result.text.forEach { ch -> emit(AgentStreamEvent.TextDelta(ch.toString())) }
            emit(AgentStreamEvent.Finished(result))
        } catch (e: Exception) {
            emit(AgentStreamEvent.Error(e.message ?: "Erro desconhecido"))
        }
    }
}
