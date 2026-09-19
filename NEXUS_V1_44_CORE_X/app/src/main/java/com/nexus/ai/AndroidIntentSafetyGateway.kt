package com.nexus.ai

import android.net.Uri
import java.net.IDN
import java.util.Locale

/**
 * V1.27: final, side-effect-free gateway for external Android intents.
 * It validates a fixed action type and a public HTTPS URI before execution.
 */
object AndroidIntentSafetyGateway {
    data class Decision(val allowed: Boolean, val reason: String, val normalizedUri: Uri? = null)

    fun validateOpenUrl(rawUrl: String): Decision {
        val value = rawUrl.trim()
        if (value.length > 2048) return Decision(false, "URL excede o limite de 2048 caracteres.")
        if (!value.startsWith("https://", ignoreCase = true)) return Decision(false, "Somente URLs HTTPS são permitidas.")
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return Decision(false, "URL inválida.")
        if (uri.scheme?.lowercase(Locale.US) != "https") return Decision(false, "Esquema não permitido.")
        if (!uri.userInfo.isNullOrBlank()) return Decision(false, "Credenciais embutidas na URL não são permitidas.")
        if (!uri.fragment.isNullOrBlank()) return Decision(false, "Fragmentos de URL não são permitidos neste gateway.")
        val host = uri.host?.trim()?.lowercase(Locale.US)
        if (host.isNullOrBlank()) return Decision(false, "Host HTTPS ausente.")
        if (uri.port != -1 && uri.port != 443) return Decision(false, "Somente HTTPS na porta padrão 443 é permitido.")
        val asciiHost = runCatching { IDN.toASCII(host) }.getOrNull() ?: return Decision(false, "Host inválido.")
        if (asciiHost.length > 253 || asciiHost.startsWith(".") || asciiHost.endsWith(".")) return Decision(false, "Host inválido.")
        if (asciiHost.contains("..")) return Decision(false, "Host inválido.")
        if (asciiHost == "localhost" || asciiHost.endsWith(".localhost")) return Decision(false, "Host local não permitido.")
        if (asciiHost == "0.0.0.0" || asciiHost == "127.0.0.1" || asciiHost == "::1") return Decision(false, "Endereço local não permitido.")
        if (asciiHost.any { it.isWhitespace() || it == '/' || it == '\\' }) return Decision(false, "Host contém caracteres inválidos.")
        return Decision(true, "Destino HTTPS validado pelo Intent Safety Gateway.", uri)
    }
}
