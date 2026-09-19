package com.nexus.ai

import kotlinx.coroutines.delay
import kotlin.math.min

/** V1.16: conservative recovery policy for multi-step execution. */
enum class RecoveryFailureType {
    EXECUTION_ERROR,
    VERIFICATION_FAILED,
    PERMISSION_DENIED,
    TIMEOUT,
    UNKNOWN_STATE
}

enum class RecoveryAction {
    RETRY,
    FALLBACK,
    REQUEST_CONFIRMATION,
    STOP
}

data class RecoveryDecision(
    val action: RecoveryAction,
    val failureType: RecoveryFailureType,
    val reason: String,
    val attempt: Int,
    val fallbackTool: String? = null,
    val delayMs: Long = 0L
)

class NexusRecoveryEngine(
    private val journal: AgentJournal,
    private val taskId: String,
    private val cycleId: String? = null,
    private val maxRetries: Int = 1,
    private val fallbackTools: Map<String, String> = emptyMap()
) {
    fun classify(exception: Throwable? = null, verification: VerificationStatus? = null): RecoveryFailureType = when {
        verification == VerificationStatus.UNKNOWN -> RecoveryFailureType.UNKNOWN_STATE
        verification == VerificationStatus.FAILED -> RecoveryFailureType.VERIFICATION_FAILED
        exception is java.util.concurrent.TimeoutException -> RecoveryFailureType.TIMEOUT
        exception != null -> RecoveryFailureType.EXECUTION_ERROR
        else -> RecoveryFailureType.UNKNOWN_STATE
    }

    fun decide(toolName: String, failureType: RecoveryFailureType, attempt: Int): RecoveryDecision {
        val fallback = fallbackTools[toolName]
        val decision = when (failureType) {
            RecoveryFailureType.EXECUTION_ERROR -> {
                if (attempt <= maxRetries) RecoveryDecision(RecoveryAction.RETRY, failureType,
                    "Erro de execução; retry permitido porque a falha ocorreu antes de confirmação.", attempt,
                    delayMs = backoff(attempt))
                else if (fallback != null) RecoveryDecision(RecoveryAction.FALLBACK, failureType,
                    "Retries esgotados; fallback registrado para a ferramenta.", attempt, fallback)
                else RecoveryDecision(RecoveryAction.STOP, failureType,
                    "Retries esgotados e nenhum fallback seguro registrado.", attempt)
            }
            RecoveryFailureType.TIMEOUT -> RecoveryDecision(
                RecoveryAction.STOP, failureType,
                "Timeout não pode ser repetido automaticamente sem conhecer o efeito produzido.", attempt
            )
            RecoveryFailureType.VERIFICATION_FAILED -> RecoveryDecision(
                RecoveryAction.STOP, failureType,
                "A execução ocorreu, mas a verificação falhou; não repetir automaticamente.", attempt
            )
            RecoveryFailureType.PERMISSION_DENIED -> RecoveryDecision(
                RecoveryAction.REQUEST_CONFIRMATION, failureType,
                "A autorização foi negada; nova execução exige decisão explícita.", attempt
            )
            RecoveryFailureType.UNKNOWN_STATE -> RecoveryDecision(
                RecoveryAction.STOP, failureType,
                "Estado indeterminado; reexecução automática bloqueada para evitar duplicidade.", attempt
            )
        }
        journal.recoveryEvent(taskId, toolName, decision, cycleId)
        return decision
    }

    suspend fun waitBeforeRetry(decision: RecoveryDecision) {
        if (decision.action == RecoveryAction.RETRY && decision.delayMs > 0) delay(decision.delayMs)
    }

    private fun backoff(attempt: Int): Long = min(2_000L, 250L * (1L shl (attempt - 1).coerceIn(0, 3)))
}
