package com.nexus.ai.security

/** Capacidade lógica concedida a uma ferramenta. Não é equivalente à permissão Android. */
enum class CapabilityKind {
    TOOL_EXECUTION,
    DEVICE_INFO,
    OPEN_EXTERNAL_APP,
    CLIPBOARD_WRITE
}
