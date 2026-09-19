package com.nexus.ai

import com.nexus.ai.security.CapabilityKind
import com.nexus.ai.security.CapabilityCatalog
import com.nexus.ai.security.PermissionKind
import com.nexus.ai.security.RiskLevel

/** V1.26: immutable allowlist of official Android actions known to NEXUS. */
data class SafeAndroidActionDefinition(
    val name: String,
    val capability: CapabilityKind,
    val permission: PermissionKind,
    val readOnly: Boolean,
    val risk: RiskLevel,
    val allowedInBackground: Boolean,
    val contractRequired: Boolean = true
)

data class AndroidCatalogValidation(
    val valid: Boolean,
    val action: String,
    val issues: List<String>
)

class AndroidActionRegistry private constructor(
    private val actions: Map<String, NexusTool>
) {
    fun get(name: String): NexusTool? = actions[name]
    fun names(): List<String> = actions.keys.sorted()
    fun definitions(): List<SafeAndroidActionDefinition> = actions.values.map { it.safeDefinition() }.sortedBy { it.name }
    fun tools(): List<NexusTool> = actions.values.toList()

    /** Planner/Context can inspect this catalog; inspection never grants authority. */
    fun catalogText(): String = definitions().joinToString("\n") {
        "${it.name}: capability=${it.capability.name}; permission=${it.permission.name}; readOnly=${it.readOnly}; risk=${it.risk.name}; background=${it.allowedInBackground}"
    }

    fun validate(): List<AndroidCatalogValidation> = actions.values.map { validate(it) }

    companion object {
        fun create(context: android.content.Context): AndroidActionRegistry {
            val candidates = listOf<NexusTool>(
                DeviceInfoToolForRegistry(context.applicationContext),
                OpenUrlToolForRegistry(context.applicationContext),
                ClipboardWriteTool(context.applicationContext)
            )
            val allowlist = setOf("device_info", "open_url", "clipboard_write")
            val accepted = linkedMapOf<String, NexusTool>()
            candidates.forEach { tool ->
                if (tool.definition.name in allowlist && validate(tool).valid) accepted[tool.definition.name] = tool
            }
            return AndroidActionRegistry(accepted)
        }

        private fun validate(tool: NexusTool): AndroidCatalogValidation {
            val issues = mutableListOf<String>()
            if (tool !is AndroidActionAdapter) issues += "AÇÃO_ANDROID_SEM_CONTRATO"
            val contract = (tool as? AndroidActionAdapter)?.actionContract()
            if (contract == null) issues += "CONTRATO_AUSENTE"
            else {
                if (contract.action != tool.definition.name) issues += "CONTRATO_ACTION_MISMATCH"
                if (contract.preconditions.isEmpty()) issues += "PRECONDITION_AUSENTE"
                if (contract.postconditions.isEmpty()) issues += "POSTCONDITION_AUSENTE"
            }
            val spec = CapabilityCatalog.spec(tool.definition.capability)
            if (tool.definition.readOnly && spec.risk == RiskLevel.CRITICAL) issues += "READONLY_COMBINATION_UNSAFE"
            if (!spec.allowedInBackground && contract?.preconditions?.none { it.id == "foreground" } == true) issues += "BACKGROUND_INCOMPATIBLE_SEM_CONDICAO_FOREGROUND"
            return AndroidCatalogValidation(issues.isEmpty(), tool.definition.name, issues)
        }
    }
}

private fun NexusTool.safeDefinition(): SafeAndroidActionDefinition {
    val spec = CapabilityCatalog.spec(definition.capability)
    return SafeAndroidActionDefinition(
        name = definition.name,
        capability = definition.capability,
        permission = definition.permission,
        readOnly = definition.readOnly,
        risk = spec.risk,
        allowedInBackground = spec.allowedInBackground
    )
}

/** Registry-only adapter wrappers keep the official catalog contract-driven. */
private class DeviceInfoToolForRegistry(private val context: android.content.Context) : AndroidActionAdapter {
    override val precondition = "Android PackageManager disponível."
    override val postcondition = "Informações retornadas correspondem ao pacote do NEXUS."
    override val definition = NexusToolDefinition(
        "device_info", "Retorna informações básicas e não sensíveis do dispositivo Android.",
        "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}", true,
        PermissionKind.TOOL_EXECUTION, CapabilityKind.DEVICE_INFO
    )
    override suspend fun execute(argumentsJson: String): ToolResult {
        val pm = context.packageManager
        val version = runCatching { pm.getPackageInfo(context.packageName, 0).versionName ?: "unknown" }.getOrDefault("unknown")
        return ToolResult(definition.name, definition.name, "Android=${android.os.Build.VERSION.RELEASE}; sdk=${android.os.Build.VERSION.SDK_INT}; appVersion=$version", true)
    }
}

private class OpenUrlToolForRegistry(private val context: android.content.Context) : AndroidActionAdapter {
    override val precondition = "URL HTTPS válida e aplicativo compatível disponível."
    override val postcondition = "Solicitação de abertura HTTPS aceita pelo Android; estado do aplicativo externo requer evidência independente."
    override val definition = NexusToolDefinition(
        "open_url", "Abre uma URL HTTPS em um aplicativo externo compatível.",
        "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\",\"pattern\":\"^https://.+\"}},\"required\":[\"url\"],\"additionalProperties\":false}", false,
        PermissionKind.TOOL_EXECUTION, CapabilityKind.OPEN_EXTERNAL_APP
    )
    override suspend fun execute(argumentsJson: String): ToolResult {
        val raw = Regex("\\\"url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(argumentsJson)?.groupValues?.get(1)
            ?: return ToolResult(definition.name, definition.name, "URL ausente.", false)
        val gateway = AndroidIntentSafetyGateway.validateOpenUrl(raw)
        if (!gateway.allowed) return ToolResult(definition.name, definition.name, gateway.reason, false)
        val uri = gateway.normalizedUri ?: return ToolResult(definition.name, definition.name, "URL não pôde ser normalizada.", false)
        return try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri).apply {
                addCategory(android.content.Intent.CATEGORY_BROWSABLE); addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) == null) ToolResult(definition.name, definition.name, "Nenhum aplicativo compatível encontrado.", false)
            else { context.startActivity(intent); ToolResult(definition.name, definition.name, "Solicitação de abertura enviada ao Android.", true) }
        } catch (e: Exception) { ToolResult(definition.name, definition.name, "Falha ao abrir URL: ${e.message}", false) }
    }
    override fun verifyResult(argumentsJson: String, result: ToolResult): VerificationResult =
        if (result.success) VerificationResult(VerificationStatus.UNKNOWN, "O Android aceitou a abertura, mas o aplicativo exibido não pode ser confirmado.")
        else VerificationResult(VerificationStatus.FAILED, result.output)
}
