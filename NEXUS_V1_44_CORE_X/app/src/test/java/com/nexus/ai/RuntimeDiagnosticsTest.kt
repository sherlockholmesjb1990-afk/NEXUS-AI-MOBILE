package com.nexus.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDiagnosticsTest {
    @Test fun cycleSummaryReconstructsObservedStages() {
        val events = listOf(
            AgentEvent("OBSERVATION", "state"),
            AgentEvent("ACTION_SELECTION_ALLOWED", "clipboard_write"),
            AgentEvent("ANDROID_RESULT_EXECUTED", "clipboard_write"),
            AgentEvent("ANDROID_EVIDENCE", "clipboard"),
            AgentEvent("CONTRACT_POSTCONDITION", "confirmed"),
            AgentEvent("RECOVERY_DECISION", "none")
        )
        val summary = NexusDiagnosticSnapshot(events, emptyList(), emptyList(), emptyList()).cycleSummary()
        assertTrue(summary.contains("OBSERVE"))
        assertTrue(summary.contains("DECIDE"))
        assertTrue(summary.contains("ACT"))
        assertTrue(summary.contains("EVIDENCE"))
        assertTrue(summary.contains("VERIFY"))
        assertTrue(summary.contains("RECOVER"))
    }

    @Test fun staleEvidenceIsDetected() {
        val now = 100_000L
        val snapshot = NexusDiagnosticSnapshot(
            emptyList(), emptyList(),
            listOf(DiagnosticEvidence("clipboard_write", "test", "clipboard", "fact", 1.0, now - 30_001)),
            emptyList()
        )
        assertTrue(snapshot.staleEvidence(now).size == 1)
    }


    @Test
    fun cycleIsolationKeepsEventsSeparated() {
        val snapshot = NexusDiagnosticSnapshot(
            listOf(
                AgentEvent("ANDROID_RESULT_EXECUTED", "A", cycleId = "cycle-A"),
                AgentEvent("ANDROID_EVIDENCE", "B", cycleId = "cycle-B")
            ),
            emptyList(), emptyList(), emptyList()
        )
        assertEquals("ACT", snapshot.cycleSummary("cycle-A"))
    }
}