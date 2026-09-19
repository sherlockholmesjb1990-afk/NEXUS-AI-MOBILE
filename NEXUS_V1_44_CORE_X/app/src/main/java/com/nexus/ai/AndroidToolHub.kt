package com.nexus.ai

import android.content.Context
import com.nexus.ai.security.CapabilityKind
import com.nexus.ai.security.CapabilityRegistry
import com.nexus.ai.security.PermissionKind

/**
 * Ponto único para registrar ferramentas Android oficiais do NEXUS.
 * V1.10 começa deliberadamente com ferramentas pequenas e controladas.
 */
class AndroidToolHub(
    private val context: Context,
    private val capabilities: CapabilityRegistry = CapabilityRegistry()
) {
    fun stateObservationAdapter(): ObservationAdapter = AndroidStateObservationAdapter(context)

    fun actionRegistry(): AndroidActionRegistry = AndroidActionRegistry.create(context.applicationContext)

    fun evidenceSources(): List<AndroidStateEvidenceSource> = listOf(
        AndroidClipboardEvidenceSource(context.applicationContext)
    )

    fun registerInto(registry: ToolRegistry) {
        val catalog = actionRegistry()
        catalog.tools().forEach { registry.register(it) }
    }
}

