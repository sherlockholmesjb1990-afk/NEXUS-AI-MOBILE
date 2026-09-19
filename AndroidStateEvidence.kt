package com.nexus.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.json.JSONObject

/**
 * V1.29: read-only evidence collected independently from an Android action's execute() call.
 * Evidence carries provenance and age so verification can remain conservative.
 */
data class AndroidStateEvidence(
    val source: String,
    val subjectId: String,
    val fact: String,
    val confidence: Double,
    val observedAt: Long = System.currentTimeMillis(),
    val cycleId: String? = null
) {
    init { require(confidence in 0.0..1.0) }

    fun ageMs(now: Long = System.currentTimeMillis()): Long = (now - observedAt).coerceAtLeast(0L)
}

interface AndroidStateEvidenceSource {
    val source: String
    fun observe(action: String, argumentsJson: String, result: ToolResult): List<AndroidStateEvidence>
}

/** Independent clipboard read-back. It never writes and never grants authority. */
class AndroidClipboardEvidenceSource(private val context: Context) : AndroidStateEvidenceSource {
    override val source = "android_clipboard_observation"

    override fun observe(action: String, argumentsJson: String, result: ToolResult): List<AndroidStateEvidence> {
        if (action != "clipboard_write" || !result.success) return emptyList()
        val expected = runCatching { JSONObject(argumentsJson).getString("text") }.getOrNull()
            ?: return listOf(AndroidStateEvidence(source, "android:clipboard", "Texto esperado não pôde ser reconstruído.", 0.0))
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return listOf(AndroidStateEvidence(source, "android:clipboard", "ClipboardManager indisponível.", 0.0))
        val clip: ClipData? = runCatching { clipboard.primaryClip }.getOrNull()
        val actual = runCatching { clip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
        return when {
            actual == expected -> listOf(AndroidStateEvidence(source, "android:clipboard", "Clipboard observado contém exatamente o texto esperado.", 1.0))
            actual == null -> listOf(AndroidStateEvidence(source, "android:clipboard", "Clipboard não forneceu conteúdo observável.", 0.0))
            else -> listOf(AndroidStateEvidence(source, "android:clipboard", "Clipboard observado contém conteúdo diferente do esperado.", 1.0))
        }
    }
}

object AndroidEvidenceCorrelator {
    fun verifyClipboardWrite(argumentsJson: String, evidence: List<AndroidStateEvidence>, maxEvidenceAgeMs: Long = 30_000L): VerificationResult {
        val expected = runCatching { JSONObject(argumentsJson).getString("text") }.getOrNull()
            ?: argumentsJson.takeIf { it.isNotBlank() }
            ?: return VerificationResult(VerificationStatus.UNKNOWN, "Texto esperado não pôde ser reconstruído para correlação.")
        return verifyClipboardWrite(expected, evidence, maxEvidenceAgeMs)
    }

    /** Pure correlation overload used by deterministic tests; it performs no Android I/O. */
    fun verifyClipboardWrite(expected: String, evidence: List<AndroidStateEvidence>, maxEvidenceAgeMs: Long = 30_000L, now: Long = System.currentTimeMillis()): VerificationResult {
        require(maxEvidenceAgeMs >= 0L)
        val relevant = evidence.filter {
            it.source == "android_clipboard_observation" &&
                it.subjectId == "android:clipboard" &&
                it.ageMs(now) <= maxEvidenceAgeMs
        }
        if (relevant.isEmpty()) return VerificationResult(VerificationStatus.UNKNOWN, "Nenhuma evidência independente de clipboard atual foi coletada (ausente, irrelevante ou stale).")
        val newest = relevant.maxByOrNull { it.observedAt }!!
        return when {
            newest.confidence >= 1.0 && newest.fact.contains("exatamente o texto esperado") ->
                VerificationResult(VerificationStatus.CONFIRMED, "Postcondition confirmada por leitura independente do clipboard.")
            newest.confidence > 0.0 && newest.fact.contains("conteúdo diferente") ->
                VerificationResult(VerificationStatus.FAILED, "Clipboard observado por fonte independente, mas o conteúdo não corresponde ao esperado.")
            else -> VerificationResult(VerificationStatus.UNKNOWN, "Evidência independente insuficiente para confirmar clipboard. Texto esperado=${expected.length} caracteres.")
        }
    }
}
