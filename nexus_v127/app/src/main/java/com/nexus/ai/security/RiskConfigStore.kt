package com.nexus.ai.security

import android.content.Context

/** V1.20: persists only administrative risk thresholds; no task/user content is stored here. */
class RiskConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("nexus_risk_policy", Context.MODE_PRIVATE)

    fun get(): RiskScoringConfig = RiskScoringConfig(
        mediumThreshold = prefs.getInt("medium", 35),
        highThreshold = prefs.getInt("high", 60),
        criticalThreshold = prefs.getInt("critical", 85)
    )

    fun set(config: RiskScoringConfig): RiskScoringConfig {
        prefs.edit()
            .putInt("medium", config.mediumThreshold)
            .putInt("high", config.highThreshold)
            .putInt("critical", config.criticalThreshold)
            .apply()
        return config
    }
}
