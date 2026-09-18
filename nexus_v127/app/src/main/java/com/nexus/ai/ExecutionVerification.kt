package com.nexus.ai

/** Resultado da verificação pós-execução de uma ferramenta. */
enum class VerificationStatus {
    CONFIRMED,
    FAILED,
    UNKNOWN
}

data class VerificationResult(
    val status: VerificationStatus,
    val detail: String
)
