package com.nexus.ai.security

/** V1.20: deterministic operational risk scoring. This is advisory/restrictive only. */
enum class RequestOrigin { USER, AGENT, SYSTEM, PLUGIN }
enum class UserState { ACTIVE, IDLE, UNKNOWN }

data class RiskFactor(val name: String, val points: Int, val explanation: String)

data class RiskScore(
    val value: Int,
    val band: RiskBand,
    val factors: List<RiskFactor>
) {
    fun explain(): String = factors.joinToString("; ") { "${it.name}=${it.points}" }
}

enum class RiskBand { LOW, MEDIUM, HIGH, CRITICAL }

data class RiskScoringConfig(
    val mediumThreshold: Int = 35,
    val highThreshold: Int = 60,
    val criticalThreshold: Int = 85
) {
    init {
        require(mediumThreshold in 1..100)
        require(highThreshold in mediumThreshold..100)
        require(criticalThreshold in highThreshold..100)
    }
}

object RiskScorer {
    fun score(context: PolicyContext, config: RiskScoringConfig = RiskScoringConfig()): RiskScore {
        val factors = mutableListOf<RiskFactor>()
        val capabilityPoints = when (CapabilityCatalog.spec(context.capability).risk) {
            RiskLevel.LOW -> 10
            RiskLevel.MEDIUM -> 30
            RiskLevel.HIGH -> 60
            RiskLevel.CRITICAL -> 85
        }
        factors += RiskFactor("capability", capabilityPoints, "Risco base da capability ${context.capability.name}.")

        if (!context.readOnly) factors += RiskFactor("mutability", 15, "A ferramenta pode alterar estado.")
        if (context.background) factors += RiskFactor("background", 20, "Execução em background aumenta a restrição operacional.")

        val permissionPoints = when (context.permission) {
            PermissionKind.DELETE_FILES -> 30
            PermissionKind.WRITE_FILES -> 20
            PermissionKind.CAMERA, PermissionKind.MICROPHONE, PermissionKind.LOCATION -> 15
            PermissionKind.NETWORK, PermissionKind.WEB_SEARCH -> 10
            else -> 0
        }
        if (permissionPoints > 0) factors += RiskFactor("permission", permissionPoints, "Sensibilidade da permissão ${context.permission.name}.")

        val originPoints = when (context.requestOrigin) {
            RequestOrigin.USER -> 0
            RequestOrigin.AGENT -> 5
            RequestOrigin.SYSTEM -> 10
            RequestOrigin.PLUGIN -> 15
        }
        if (originPoints > 0) factors += RiskFactor("origin", originPoints, "Origem da solicitação: ${context.requestOrigin.name}.")

        context.verificationConfidence?.let { confidence ->
            val clamped = confidence.coerceIn(0.0, 1.0)
            val points = ((1.0 - clamped) * 20.0).toInt()
            if (points > 0) factors += RiskFactor("verification_confidence", points, "Baixa confiança na verificação anterior.")
        }

        if (context.userState == UserState.IDLE) factors += RiskFactor("user_state", 5, "Usuário explicitamente marcado como ocioso.")

        val value = factors.sumOf { it.points }.coerceAtMost(100)
        val band = when {
            value >= config.criticalThreshold -> RiskBand.CRITICAL
            value >= config.highThreshold -> RiskBand.HIGH
            value >= config.mediumThreshold -> RiskBand.MEDIUM
            else -> RiskBand.LOW
        }
        return RiskScore(value, band, factors)
    }
}
