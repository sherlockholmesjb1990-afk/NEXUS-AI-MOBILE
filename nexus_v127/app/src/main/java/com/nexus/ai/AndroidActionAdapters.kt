package com.nexus.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.nexus.ai.security.CapabilityKind
import com.nexus.ai.security.PermissionKind
import org.json.JSONObject

/** Contract shared by Android actions: explicit pre/postconditions and no authority escalation. */
interface AndroidActionAdapter : NexusTool {
    val precondition: String
    val postcondition: String

    /** V1.25 structured contract; evaluators remain authoritative and side-effect free. */
    fun actionContract(): AndroidActionContract = AndroidActionContract(
        action = definition.name,
        preconditions = listOf(
            AndroidPrecondition("foreground", "Ação Android deve ocorrer em foreground.")
        ),
        postconditions = listOf(
            AndroidPostcondition("tool_verification", postcondition)
        )
    )
}

/** V1.24: minimal foreground-only action adapter for copying user-requested text. */
class ClipboardWriteTool(private val context: Context) : AndroidActionAdapter {
    override val precondition = "Android ClipboardManager disponível; execução autorizada em foreground."
    override val postcondition = "O clipboard contém o texto enviado por esta ação."

    override val definition = NexusToolDefinition(
        name = "clipboard_write",
        description = "Copia texto fornecido pelo usuário para a área de transferência do Android.",
        parametersJson = """{"type":"object","properties":{"text":{"type":"string","maxLength":4000}},"required":["text"],"additionalProperties":false}""",
        readOnly = false,
        permission = PermissionKind.TOOL_EXECUTION,
        capability = CapabilityKind.CLIPBOARD_WRITE
    )

    override suspend fun execute(argumentsJson: String): ToolResult {
        val text = runCatching { JSONObject(argumentsJson).getString("text") }.getOrNull()
            ?: return ToolResult(definition.name, definition.name, "Texto ausente.", false)
        if (text.length > 4000) return ToolResult(definition.name, definition.name, "Texto excede o limite de 4000 caracteres.", false)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return ToolResult(definition.name, definition.name, "ClipboardManager indisponível.", false)
        clipboard.setPrimaryClip(ClipData.newPlainText("NEXUS", text))
        return ToolResult(definition.name, definition.name, "Texto enviado ao clipboard.", true)
    }

    override fun verifyResult(argumentsJson: String, result: ToolResult): VerificationResult {
        if (!result.success) return VerificationResult(VerificationStatus.FAILED, result.output)
        val expected = runCatching { JSONObject(argumentsJson).getString("text") }.getOrNull()
            ?: return VerificationResult(VerificationStatus.UNKNOWN, "Não foi possível reconstruir o texto esperado.")
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return VerificationResult(VerificationStatus.UNKNOWN, "ClipboardManager indisponível para verificação.")
        val clip = runCatching { clipboard.primaryClip }.getOrNull()
            ?: return VerificationResult(VerificationStatus.UNKNOWN, "Clipboard não pôde ser observado.")
        val actual = runCatching { clip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
        return if (actual == expected) {
            VerificationResult(VerificationStatus.CONFIRMED, "Postcondition confirmada: clipboard contém exatamente o texto enviado.")
        } else if (actual == null) {
            VerificationResult(VerificationStatus.UNKNOWN, "Clipboard não forneceu evidência suficiente para confirmar o pós-estado.")
        } else {
            VerificationResult(VerificationStatus.FAILED, "Clipboard observado, mas o conteúdo não corresponde ao esperado.")
        }
    }
}
