package com.nexus.ai

import android.content.Context
import com.nexus.ai.security.CapabilityKind
import com.nexus.ai.security.PermissionKind
import org.json.JSONObject

/** Manifesto local, versionado e declarativo de uma ferramenta/plugin. */
data class NexusPluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val origin: String,
    val risk: String,
    val permission: PermissionKind,
    val enabled: Boolean,
    val capability: CapabilityKind = CapabilityKind.TOOL_EXECUTION,
    val toolName: String? = null
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("version", version)
        put("description", description); put("origin", origin); put("risk", risk)
        put("permission", permission.name); put("enabled", enabled)
        put("capability", capability.name); put("toolName", toolName)
    }

    companion object {
        fun fromJson(o: JSONObject) = NexusPluginManifest(
            o.getString("id"), o.getString("name"), o.getString("version"),
            o.getString("description"), o.getString("origin"), o.getString("risk"),
            PermissionKind.valueOf(o.getString("permission")), o.optBoolean("enabled", true),
            runCatching { CapabilityKind.valueOf(o.optString("capability", CapabilityKind.TOOL_EXECUTION.name)) }
                .getOrDefault(CapabilityKind.TOOL_EXECUTION),
            o.optString("toolName").takeIf { it.isNotBlank() && it != "null" }
        )
    }
}

class PluginManager(context: Context) {
    private val prefs = context.getSharedPreferences("nexus_plugins", Context.MODE_PRIVATE)
    private val key = "manifests"

    fun manifests(): List<NexusPluginManifest> {
        val raw = prefs.getString(key, null)
        if (raw.isNullOrBlank()) return defaults()
        return runCatching {
            val a = org.json.JSONArray(raw)
            (0 until a.length()).map { NexusPluginManifest.fromJson(a.getJSONObject(it)) }
        }.getOrElse { defaults() }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val updated = manifests().map { if (it.id == id) it.copy(enabled = enabled) else it }
        save(updated)
    }

    fun importManifest(json: String): Result<NexusPluginManifest> = runCatching {
        val plugin = NexusPluginManifest.fromJson(JSONObject(json))
        require(plugin.id.matches(Regex("[a-z0-9._-]+"))) { "ID inválido" }
        require(plugin.name.isNotBlank()) { "Nome ausente" }
        require(plugin.version.isNotBlank()) { "Versão ausente" }
        require(plugin.permission != PermissionKind.DELETE_FILES || plugin.risk.uppercase() == "HIGH") { "DELETE_FILES exige risco HIGH" }
        require(plugin.capability in CapabilityKind.entries) { "Capability inválida" }
        val spec = com.nexus.ai.security.CapabilityCatalog.spec(plugin.capability)
        if (spec.risk.name == "HIGH" || spec.risk.name == "CRITICAL") require(plugin.risk.uppercase() == spec.risk.name || plugin.risk.uppercase() == "HIGH") { "Risco declarado incompatível com a capability ${plugin.capability.name}" }
        require(plugin.origin.equals("builtin", true) || plugin.origin.equals("local", true)) { "Origem não confiável: apenas builtin/local são aceitas na V1.11" }
        val updated = manifests().filterNot { it.id == plugin.id } + plugin.copy(enabled = false)
        save(updated)
        plugin
    }

    fun exportManifest(id: String): String? = manifests().firstOrNull { it.id == id }?.toJson()?.toString(2)

    private fun save(items: List<NexusPluginManifest>) {
        val a = org.json.JSONArray()
        items.forEach { a.put(it.toJson()) }
        prefs.edit().putString(key, a.toString()).apply()
    }

    private fun defaults() = listOf(
        NexusPluginManifest("core.calculator", "Calculadora", "1.0", "Cálculos matemáticos locais.", "builtin", "LOW", PermissionKind.TOOL_EXECUTION, true, CapabilityKind.TOOL_EXECUTION, "calculator"),
        NexusPluginManifest("core.clock", "Relógio", "1.0", "Data e hora local do dispositivo.", "builtin", "LOW", PermissionKind.TOOL_EXECUTION, true, CapabilityKind.TOOL_EXECUTION, "clock")
    )
}
