package com.nexus.ai

import com.nexus.ai.security.PermissionKind

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ClockTool : NexusTool {
    override val definition = NexusToolDefinition(
        name = "clock",
        description = "Retorna data e hora local do dispositivo.",
        parametersJson = """{"type":"object","properties":{},"additionalProperties":false}""",
        readOnly = true,
        permission = PermissionKind.TOOL_EXECUTION
    )

    override suspend fun execute(argumentsJson: String): ToolResult {
        val value = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("pt", "BR")).format(Date())
        return ToolResult("clock", "clock", value, true)
    }
}
