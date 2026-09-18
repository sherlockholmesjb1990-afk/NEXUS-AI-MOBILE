package com.nexus.ai

import kotlinx.coroutines.flow.Flow

interface StreamingProvider {
    fun streamText(messages: List<ChatMessage>): Flow<String>
}
