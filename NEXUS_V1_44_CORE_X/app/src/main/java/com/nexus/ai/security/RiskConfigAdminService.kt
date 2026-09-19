package com.nexus.ai.security

/** Administrative-only risk threshold configuration. The agent must not receive this service. */
class RiskConfigAdminService(
    private val store: RiskConfigStore,
    private val audit: (String, Boolean) -> Unit
) {
    fun update(medium: Int, high: Int, critical: Int): Result<RiskScoringConfig> {
        return try {
            val config = RiskScoringConfig(medium, high, critical)
            val saved = store.set(config)
            audit("RISK_CONFIG_UPDATE", true)
            Result.success(saved)
        } catch (e: IllegalArgumentException) {
            audit("RISK_CONFIG_UPDATE", false)
            Result.failure(e)
        }
    }
}
