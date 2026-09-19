package com.nexus.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusResumeABTest {
    @Test
    fun cycleAResumesOnlyFromCycleA() {
        val taskId = "task-42"
        val a = NexusResumeAnchor(taskId, "cycle-A", "resp-A", "step-2")
        val b = NexusResumeAnchor(taskId, "cycle-B", "resp-B", "step-1")

        assertTrue(a.matches(taskId, "cycle-A"))
        assertFalse(a.matches(taskId, "cycle-B"))
        assertFalse(b.matches(taskId, "cycle-A"))
        assertTrue(b.matches(taskId, "cycle-B"))
        assertEquals("resp-A", a.responseId)
        assertEquals("step-2", a.checkpoint)
    }

    @Test
    fun interruptedAThenResumeADoesNotAdoptBAnchor() {
        val taskId = "task-42"
        val persistedA = NexusResumeAnchor(taskId, "cycle-A", "resp-A", "step-2")
        val newerB = NexusResumeAnchor(taskId, "cycle-B", "resp-B", "step-1")

        val requestedCycle = "cycle-A"
        val candidates = listOf(newerB, persistedA)
        val selected = candidates.firstOrNull { it.matches(taskId, requestedCycle) }

        assertEquals("cycle-A", selected?.cycleId)
        assertEquals("resp-A", selected?.responseId)
        assertEquals("step-2", selected?.checkpoint)
        assertFalse(newerB.matches(taskId, requestedCycle))
    }

    @Test
    fun mismatchedCycleHasNoResumeAnchor() {
        val anchor = NexusResumeAnchor("task-42", "cycle-A", "resp-A", "step-2")
        val selected = if (anchor.matches("task-42", "cycle-B")) anchor else null
        assertNull(selected)
    }

    @Test
    fun sameTaskCanHoldIndependentResumeAnchorsConceptually() {
        val taskId = "task-42"
        val anchors = mapOf(
            "cycle-A" to NexusResumeAnchor(taskId, "cycle-A", "resp-A", "step-2"),
            "cycle-B" to NexusResumeAnchor(taskId, "cycle-B", "resp-B", "step-1")
        )

        assertEquals("resp-A", anchors["cycle-A"]?.responseId)
        assertEquals("resp-B", anchors["cycle-B"]?.responseId)
        assertEquals("step-2", anchors["cycle-A"]?.checkpoint)
        assertEquals("step-1", anchors["cycle-B"]?.checkpoint)
    }
}
