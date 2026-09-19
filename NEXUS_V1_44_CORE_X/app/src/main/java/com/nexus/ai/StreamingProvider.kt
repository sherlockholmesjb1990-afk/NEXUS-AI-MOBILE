package com.nexus.ai

import kotlinx.coroutines.flow.Flow

interface StreamingProvider {
    fun streamText(messages: List<ChatMessage>): Flow<String>

    /** V1.40: optional execution correlation. Implementations may forward it to the gateway. */
    fun streamText(messages: List<ChatMessage>, cycleId: String?): Flow<String> = streamText(messages)
}
