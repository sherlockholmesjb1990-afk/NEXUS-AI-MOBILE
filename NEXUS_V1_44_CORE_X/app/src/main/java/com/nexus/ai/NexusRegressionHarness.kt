package com.nexus.ai

/**
 * V1.31: deterministic, Android-I/O-free regression harness.
 * It validates the safety-critical evidence correlation contract without
 * touching the device, permissions, clipboard, camera, network or tools.
 */
data class RegressionCaseResult(
    val name: String,
    val passed: Boolean,
    val detail: String
)

data class RegressionReport(
    val version: String,
    val cases: List<RegressionCaseResult>
) {
    val passed: Int get() = cases.count { it.passed }
    val failed: Int get() = cases.size - passed
    val success: Boolean get() = failed == 0

    fun toDisplayText(): String = buildString {
        appendLine("🧪 NEXUS V1.31 — Regressão determinística")
        appendLine("Resultado: ${if (success) "PASS" else "FAIL"} ($passed/${cases.size})")
        cases.forEach { appendLine("${if (it.passed) "✅" else "❌"} ${it.name}: ${it.detail}") }
        append("Nenhuma ferramenta, permissão, câmera ou rede foi executada pelo harness.")
    }
}

object NexusRegressionHarness {
    private const val NOW = 100_000L

    fun run(): RegressionReport {
        val cases = listOf(
            runCase("clipboard confirma conteúdo") {
                val evidence = AndroidStateEvidence(
                    "android_clipboard_observation", "android:clipboard",
                    "Clipboard observado contém exatamente o texto esperado.", 1.0, NOW
                )
                val result = AndroidEvidenceCorrelator.verifyClipboardWrite(
                    "NEXUS", listOf(evidence), now = NOW
                )
                result.status == VerificationStatus.CONFIRMED
            },
            runCase("clipboard divergente falha") {
                val evidence = AndroidStateEvidence(
                    "android_clipboard_observation", "android:clipboard",
                    "Clipboard observado contém conteúdo diferente do esperado.", 1.0, NOW
                )
                val result = AndroidEvidenceCorrelator.verifyClipboardWrite(
                    "NEXUS", listOf(evidence), now = NOW
                )
                result.status == VerificationStatus.FAILED
            },
            runCase("evidência ausente permanece desconhecida") {
                val result = AndroidEvidenceCorrelator.verifyClipboardWrite(
                    "NEXUS", emptyList(), now = NOW
                )
                result.status == VerificationStatus.UNKNOWN
            },
            runCase("evidência stale não confirma") {
                val evidence = AndroidStateEvidence(
                    "android_clipboard_observation", "android:clipboard",
                    "Clipboard observado contém exatamente o texto esperado.", 1.0, NOW - 30_001
                )
                val result = AndroidEvidenceCorrelator.verifyClipboardWrite(
                    "NEXUS", listOf(evidence), now = NOW
                )
                result.status == VerificationStatus.UNKNOWN
            },
            runCase("fonte irrelevante não confirma") {
                val evidence = AndroidStateEvidence(
                    "other_source", "android:clipboard",
                    "Clipboard observado contém exatamente o texto esperado.", 1.0, NOW
                )
                val result = AndroidEvidenceCorrelator.verifyClipboardWrite(
                    "NEXUS", listOf(evidence), now = NOW
                )
                result.status == VerificationStatus.UNKNOWN
            }
        )
        return RegressionReport("1.31", cases)
    }

    private fun runCase(name: String, block: () -> Boolean): RegressionCaseResult =
        runCatching {
            if (block()) RegressionCaseResult(name, true, "contrato preservado")
            else RegressionCaseResult(name, false, "resultado inesperado")
        }.getOrElse { RegressionCaseResult(name, false, "exceção: ${it.message}") }
}
