package com.nexus.ai

import android.content.Context

class NexusSettings(context: Context) {
    private val prefs = context.getSharedPreferences("nexus_settings", Context.MODE_PRIVATE)
    var gatewayEndpoint: String
        get() = prefs.getString("gateway_endpoint", "") ?: ""
        set(value) = prefs.edit().putString("gateway_endpoint", value.trim()).apply()
    var appKey: String
        get() = prefs.getString("app_key", "") ?: ""
        set(value) = prefs.edit().putString("app_key", value).apply()

    fun provider(): AIProvider = gatewayEndpoint.takeIf { it.isNotBlank() }?.let { RemoteAIProvider(it, appKey) } ?: MockAIProvider()
}
