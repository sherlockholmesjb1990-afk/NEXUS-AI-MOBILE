package com.nexus.ai

/** V1.25: structured, deterministic contracts for Android mutations. */
data class AndroidPrecondition(val id: String, val description: String, val required: Boolean = true)
data class AndroidPostcondition(val id: String, val description: String, val required: Boolean = true)

data class AndroidActionContract(
    val action: String,
    val preconditions: List<AndroidPrecondition>,
    val postconditions: List<AndroidPostcondition>
)

enum class ContractStatus { ALLOW, BLOCK, UNKNOWN, CONFIRMED, FAILED }

data class ContractEvaluation(
    val status: ContractStatus,
    val contractId: String,
    val action: String,
    val stage: String,
    val detail: String,
    val evidence: List<String> = emptyList(),
    val evaluatedAt: Long = System.currentTimeMillis()
)

/** Evaluates contracts without granting permissions or executing actions. */
class AndroidActionContractEvaluator {
    fun precheck(
        contract: AndroidActionContract,
        background: Boolean,
        observations: List<NexusObservation>
    ): ContractEvaluation {
        val app = observations.firstOrNull { it.subjectId == "android:app" }
        if (background) return ContractEvaluation(ContractStatus.BLOCK, contractId(contract), contract.action, "PRECHECK", "Ação Android bloqueada em background.")
        if (app?.state == ObservationState.UNKNOWN) return ContractEvaluation(ContractStatus.UNKNOWN, contractId(contract), contract.action, "PRECHECK", "Estado foreground do aplicativo não pôde ser confirmado.")
        if (app != null && !app.fact.contains("foreground=true")) return ContractEvaluation(ContractStatus.BLOCK, contractId(contract), contract.action, "PRECHECK", "Precondition foreground não satisfeita.", listOf(app.fact))
        if (app == null) return ContractEvaluation(ContractStatus.UNKNOWN, contractId(contract), contract.action, "PRECHECK", "Observação android:app ausente.")
        return ContractEvaluation(ContractStatus.ALLOW, contractId(contract), contract.action, "PRECHECK", "Preconditions determinísticas satisfeitas.", listOf(app.fact))
    }

    fun postcheck(
        contract: AndroidActionContract,
        verification: VerificationResult,
        observations: List<NexusObservation>
    ): ContractEvaluation {
        val evidence = observations.map { it.fact }.take(10)
        return when (verification.status) {
            VerificationStatus.CONFIRMED -> ContractEvaluation(ContractStatus.CONFIRMED, contractId(contract), contract.action, "POSTCONDITION", "Pós-condição confirmada por verificação explícita.", evidence + verification.detail)
            VerificationStatus.FAILED -> ContractEvaluation(ContractStatus.FAILED, contractId(contract), contract.action, "POSTCONDITION", "Pós-condição falhou: ${verification.detail}", evidence)
            VerificationStatus.UNKNOWN -> ContractEvaluation(ContractStatus.UNKNOWN, contractId(contract), contract.action, "POSTCONDITION", "Pós-condição permanece UNKNOWN: ${verification.detail}", evidence)
        }
    }

    private fun contractId(contract: AndroidActionContract): String =
        "android:${contract.action}:" + contract.preconditions.joinToString("|") { it.id } + ":" + contract.postconditions.joinToString("|") { it.id }
}
