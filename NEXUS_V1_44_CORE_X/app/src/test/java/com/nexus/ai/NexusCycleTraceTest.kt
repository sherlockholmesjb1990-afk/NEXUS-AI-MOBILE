package com.nexus.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusCycleTraceTest {
    @Test fun traceIsOrderedAndDeterministicWithFakeClock() {
        var now = 1_000L
        val trace = NexusCycleTrace.begin("teste seguro") { now }
        trace.stage(CycleStageName.OBSERVE, "estado")
        now += 12
        trace.stage(CycleStageName.DECIDE, "plano")
        now += 8
        trace.stage(CycleStageName.ACT, "ação")
        now += 5
        val report = trace.finish(CycleResult.SUCCESS, "ok")

        assertEquals(listOf(CycleStageName.OBSERVE, CycleStageName.DECIDE, CycleStageName.ACT), report.stages.map { it.name })
        assertEquals(listOf(0L, 12L, 20L), report.stages.map { it.elapsedMs })
        assertEquals(25L, report.durationMs)
        assertTrue(report.cycleId.startsWith("cycle-"))
    }

    @Test fun contextKeepsOneStableCycleId() {
        val trace = NexusCycleTrace.begin("objetivo") { 1_000L }
        assertEquals(trace.cycleId, trace.context.cycleId)
        assertEquals("objetivo", trace.context.objective)
    }

    @Test fun unknownAndBlockedRemainExplicitResults() {
        var now = 10L
        val unknown = NexusCycleTrace.begin("sem evidência") { now }.apply {
            stage(CycleStageName.OBSERVE)
            stage(CycleStageName.ACT)
            stage(CycleStageName.EVIDENCE, "evidência insuficiente")
        }.finish(CycleResult.UNKNOWN)
        now += 100
        val blocked = NexusCycleTrace.begin("ação bloqueada") { now }.apply {
            stage(CycleStageName.OBSERVE)
            stage(CycleStageName.POLICY, "bloqueado")
        }.finish(CycleResult.BLOCKED)

        assertEquals(CycleResult.UNKNOWN, unknown.result)
        assertEquals(CycleResult.BLOCKED, blocked.result)
    }
}


    @Test
    fun contextCarriesStableCycleIdentity() {
        val trace = NexusCycleTrace.begin("teste") { 1000L }
        val first = trace.context
        val second = trace.context
        assertEquals(first.cycleId, second.cycleId)
        assertEquals("teste", first.objective)
    }
