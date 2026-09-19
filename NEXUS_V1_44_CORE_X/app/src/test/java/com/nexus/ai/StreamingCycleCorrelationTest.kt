package com.nexus.ai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingCycleCorrelationTest {
    @Test fun cycleAwareStreamingApiRemainsBackwardCompatible() = runBlocking {
        val provider = MockAIProvider()
        val text = provider.streamText(listOf(ChatMessage(MessageRole.USER, "teste")), "cycle-A").toList().joinToString("")
        assertTrue(text.contains("teste"))
    }

    @Test fun legacyStreamingSignatureStillWorks() = runBlocking {
        val provider: StreamingProvider = MockAIProvider()
        val text = provider.streamText(listOf(ChatMessage(MessageRole.USER, "legado"))).toList().joinToString("")
        assertTrue(text.contains("legado"))
    }
}
