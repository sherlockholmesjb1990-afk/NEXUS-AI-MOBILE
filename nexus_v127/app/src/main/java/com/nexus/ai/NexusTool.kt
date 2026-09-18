package com.nexus.ai

import com.nexus.ai.security.PermissionKind
import com.nexus.ai.security.CapabilityKind

interface NexusTool : com.nexus.ai.security.NexusToolAccess {
    val definition: NexusToolDefinition
    override val capability: CapabilityKind get() = definition.capability
    suspend fun execute(argumentsJson: String): ToolResult

    /**
     * Verifica o resultado depois da execução.
     * Ferramentas com efeitos reais devem sobrescrever este método para confirmar
     * o estado observado do sistema, em vez de apenas confiar no retorno da chamada.
     */
    fun verifyResult(argumentsJson: String, result: ToolResult): VerificationResult =
        if (result.success) {
            VerificationResult(VerificationStatus.CONFIRMED, "A ferramenta reportou execução bem-sucedida.")
        } else {
            VerificationResult(VerificationStatus.FAILED, "A ferramenta reportou falha na execução.")
        }
}

data class NexusToolDefinition(
    val name: String,
    val description: String,
    val parametersJson: String,
    val readOnly: Boolean,
    val permission: PermissionKind = PermissionKind.TOOL_EXECUTION,
    val capability: CapabilityKind = CapabilityKind.TOOL_EXECUTION
)
