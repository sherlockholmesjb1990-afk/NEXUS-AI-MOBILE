package com.nexus.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusCycleIntegrationTest {
    @Test
    fun diagnosticSnapshotSeparatesCycleAFromCycleB() {
        val snapshot = NexusDiagnosticSnapshot(
            events = listOf(
                AgentEvent("MODEL_RESPONSE", "A", cycleId = "cycle-A"),
                AgentEvent("ANDROID_RESULT_EXECUTED", "A result", cycleId = "cycle-A"),
                AgentEvent("ANDROID_EVIDENCE", "A evidence", cycleId = "cycle-A"),
                AgentEvent("CONTRACT_POSTCONDITION", "A contract", cycleId = "cycle-A"),
                AgentEvent("RECOVERY_DECISION", "A recovery", cycleId = "cycle-A"),
                AgentEvent("MODEL_RESPONSE", "B", cycleId = "cycle-B"),
                AgentEvent("ANDROID_RESULT_EXECUTED", "B result", cycleId = "cycle-B")
            ),
            actionResults = listOf(
                DiagnosticActionResult("clipboard_write", "EXECUTED", "A", 1L, "cycle-A"),
                DiagnosticActionResult("clipboard_write", "FAILED", "B", 2L, "cycle-B")
            ),
            evidence = listOf(
                DiagnosticEvidence("clipboard_write", "test", "A", "A", 1.0, 1L, "cycle-A"),
                DiagnosticEvidence("clipboard_write", "test", "B", "B", 1.0, 2L, "cycle-B")
            ),
            contracts = listOf(
                DiagnosticContract("clipboard_write", "POSTCONDITION", "CONFIRMED", "A", 1L, "cycle-A"),
                DiagnosticContract("clipboard_write", "POSTCONDITION", "FAILED", "B", 2L, "cycle-B")
            )
        )

        val aEvents = snapshot.events.filter { it.cycleId == "cycle-A" }
        val aResults = snapshot.actionResults.filter { it.cycleId == "cycle-A" }
        val aEvidence = snapshot.evidence.filter { it.cycleId == "cycle-A" }
        val aContracts = snapshot.contracts.filter { it.cycleId == "cycle-A" }

        assertEquals(5, aEvents.size)
        assertEquals(1, aResults.size)
        assertEquals(1, aEvidence.size)
        assertEquals(1, aContracts.size)
        assertTrue(aEvents.none { it.cycleId == "cycle-B" })
        assertEquals("OBSERVE → DECIDE → ACT → EVIDENCE → VERIFY → RECOVER",
            snapshot.cycleSummary("cycle-A"))
        assertEquals("DECIDE → ACT", snapshot.cycleSummary("cycle-B"))
    }
}
