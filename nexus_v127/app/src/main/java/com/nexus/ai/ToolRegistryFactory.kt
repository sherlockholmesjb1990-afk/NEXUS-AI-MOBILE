package com.nexus.ai

import android.content.Context
import com.nexus.ai.security.CapabilityRegistry

/** Fonte única para reconstrução do Tool Hub em foreground e background. */
object ToolRegistryFactory {
    fun create(context: Context, includePlugins: Boolean = true): ToolRegistry {
        val registry = ToolRegistry()
        registry.register(ClockTool())
        registry.register(CalculatorTool())
        AndroidToolHub(context.applicationContext, CapabilityRegistry()).registerInto(registry)

        if (includePlugins) {
            val pluginManager = PluginManager(context)
            val enabled = pluginManager.manifests().filter { it.enabled }.mapNotNull { it.toolName }.toSet()
            if ("clock" !in enabled) registry.remove("clock")
            if ("calculator" !in enabled) registry.remove("calculator")
        }
        return registry
    }
}
