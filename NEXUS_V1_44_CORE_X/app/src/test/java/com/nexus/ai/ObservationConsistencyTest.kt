package com.nexus.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class ObservationConsistencyTest {
    @Test
    fun reconciliationDoesNotMixDifferentCycles() {
        val observations = listOf(
            NexusObservation(
                kind = ObservationKind.TOOL, subjectId = "task-1",
                state = ObservationState.COMPLETED, fact = "ciclo A",
                confidence = 1.0, source = "test", cycleId = "cycle-A"
            ),
            NexusObservation(
                kind = ObservationKind.TOOL, subjectId = "task-1",
                state = ObservationState.BLOCKED, fact = "ciclo B",
                confidence = 1.0, source = "test", cycleId = "cycle-B"
            )
        )

        val reconciled = ObservationReconciler.reconcile(observations)

        assertEquals(2, reconciled.size)
        assertEquals(setOf("cycle-A", "cycle-B"), reconciled.map { it.cycleId }.toSet())
    }
}
