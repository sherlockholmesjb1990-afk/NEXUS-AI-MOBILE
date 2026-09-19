package com.nexus.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidEvidenceCorrelatorTest {
    private val args = "{\"text\":\"nexus-test\"}"
    private fun result() = ToolResult("call-1", "clipboard_write", "ok", true)

    @Test fun matchingClipboardIsConfirmed() {
        val evidence = listOf(AndroidStateEvidence(
            "android_clipboard_observation", "android:clipboard",
            "Clipboard observado contém exatamente o texto esperado.", 1.0
        ))
        assertEquals(VerificationStatus.CONFIRMED, AndroidEvidenceCorrelator.verifyClipboardWrite("nexus-test", evidence).status)
    }

    @Test fun mismatchingClipboardFails() {
        val evidence = listOf(AndroidStateEvidence(
            "android_clipboard_observation", "android:clipboard",
            "Clipboard observado contém conteúdo diferente do esperado.", 1.0
        ))
        assertEquals(VerificationStatus.FAILED, AndroidEvidenceCorrelator.verifyClipboardWrite("nexus-test", evidence).status)
    }

    @Test fun missingEvidenceRemainsUnknown() {
        assertEquals(VerificationStatus.UNKNOWN, AndroidEvidenceCorrelator.verifyClipboardWrite("nexus-test", emptyList()).status)
    }

    @Test fun staleEvidenceDoesNotConfirm() {
        val evidence = listOf(AndroidStateEvidence(
            "android_clipboard_observation", "android:clipboard",
            "Clipboard observado contém exatamente o texto esperado.", 1.0, observedAt = 0L
        ))
        assertEquals(VerificationStatus.UNKNOWN, AndroidEvidenceCorrelator.verifyClipboardWrite("nexus-test", evidence, now = 30_001L).status)
    }

    @Test fun unrelatedEvidenceDoesNotConfirm() {
        val evidence = listOf(AndroidStateEvidence(
            "other_source", "android:clipboard",
            "Clipboard observado contém exatamente o texto esperado.", 1.0
        ))
        assertEquals(VerificationStatus.UNKNOWN, AndroidEvidenceCorrelator.verifyClipboardWrite("nexus-test", evidence).status)
    }

    @Test fun successfulToolResultCanBeUsedByTheEvidenceSourceContract() {
        assertEquals(true, result().success)
    }
}
