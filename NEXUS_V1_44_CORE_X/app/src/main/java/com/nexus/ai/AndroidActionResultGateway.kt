package com.nexus.ai

/**
 * V1.28: normalizes Android action outcomes without treating a successful API call
 * as proof that the requested real-world post-state exists.
 */
enum class AndroidActionResultState { EXECUTED, REJECTED, FAILED, UNKNOWN }

data class AndroidActionResult(
    val state: AndroidActionResultState,
    val action: String,
    val detail: String,
    val evidence: List<String> = emptyList(),
    val normalizedAt: Long = System.currentTimeMillis(),
    val cycleId: String? = null
)

class AndroidActionResultGateway {
    fun normalize(
        action: String,
        result: ToolResult,
        verification: VerificationResult? = null,
        cycleId: String? = null
    ): AndroidActionResult {
        if (!result.success) {
            val rejected = isRejection(result.output)
            return AndroidActionResult(
                if (rejected) AndroidActionResultState.REJECTED else AndroidActionResultState.FAILED,
                action,
                result.output.take(800),
                cycleId = cycleId
            )
        }

        // A verification failure means the attempted action did not produce the expected state.
        if (verification?.status == VerificationStatus.FAILED) {
            return AndroidActionResult(AndroidActionResultState.FAILED, action, verification.detail.take(800), cycleId = cycleId)
        }

        // UNKNOWN is deliberately preserved. A startActivity/startService acknowledgement,
        // for example, proves acceptance by the Android API but not that the external state changed.
        if (verification?.status == VerificationStatus.UNKNOWN) {
            return AndroidActionResult(
                AndroidActionResultState.UNKNOWN,
                action,
                verification.detail.take(800),
                listOf("tool_result_success=true", "verification=UNKNOWN"),
                cycleId = cycleId
            )
        }

        return AndroidActionResult(
            AndroidActionResultState.EXECUTED,
            action,
            if (verification?.status == VerificationStatus.CONFIRMED) {
                "Execução realizada e pós-condição confirmada."
            } else {
                "Android reportou execução; confirmação independente ainda não foi fornecida."
            },
            if (verification?.status == VerificationStatus.CONFIRMED) listOf("verification=CONFIRMED") else emptyList(),
            cycleId = cycleId
        )
    }

    private fun isRejection(detail: String): Boolean {
        val text = detail.lowercase()
        return listOf("bloquead", "rejeitad", "negad", "não autoriz", "nao autoriz", "permission denied", "não registrado", "nao registrado")
            .any(text::contains)
    }
}
