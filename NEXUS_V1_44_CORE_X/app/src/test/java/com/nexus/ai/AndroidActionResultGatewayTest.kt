package com.nexus.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidActionResultGatewayTest {
    private val gateway = AndroidActionResultGateway()

    @Test fun successfulWithoutVerificationIsExecuted() {
        val r = gateway.normalize("open_url", ToolResult("1", "open_url", "accepted", true))
        assertEquals(AndroidActionResultState.EXECUTED, r.state)
    }

    @Test fun rejectedToolIsRejected() {
        val r = gateway.normalize("open_url", ToolResult("1", "open_url", "Ação bloqueada pela política", false))
        assertEquals(AndroidActionResultState.REJECTED, r.state)
    }

    @Test fun failedToolIsFailed() {
        val r = gateway.normalize("clipboard_write", ToolResult("1", "clipboard_write", "falha de execução", false))
        assertEquals(AndroidActionResultState.FAILED, r.state)
    }

    @Test fun unknownVerificationRemainsUnknown() {
        val v = VerificationResult(VerificationStatus.UNKNOWN, "estado externo não confirmado")
        val r = gateway.normalize("open_url", ToolResult("1", "open_url", "accepted", true), v)
        assertEquals(AndroidActionResultState.UNKNOWN, r.state)
    }

    @Test fun failedVerificationFails() {
        val v = VerificationResult(VerificationStatus.FAILED, "postcondition falhou")
        val r = gateway.normalize("clipboard_write", ToolResult("1", "clipboard_write", "accepted", true), v)
        assertEquals(AndroidActionResultState.FAILED, r.state)
    }

    @org.junit.Test
    fun cycleIdIsPreservedAcrossNormalizedOutcomes() {
        val cycleId = "cycle-test-001"
        val result = gateway.normalize("clipboard_write", ToolResult("call-1", "clipboard_write", "falhou", false), cycleId = cycleId)
        org.junit.Assert.assertEquals(cycleId, result.cycleId)
    }
}
