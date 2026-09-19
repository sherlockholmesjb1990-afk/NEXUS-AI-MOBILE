package com.nexus.ai

/** V1.27: resolves requested Android actions only from the immutable safe catalog. */
class AndroidActionSelector(
    private val catalog: AndroidActionRegistry,
    private val journal: AgentJournal? = null
) {
    enum class Status { SELECTED, REJECTED, NOT_ANDROID }

    data class Selection(
        val status: Status,
        val requestedName: String,
        val resolvedName: String? = null,
        val tool: NexusTool? = null,
        val reason: String
    )

    fun select(requestedName: String, taskId: String? = null, cycleId: String? = null): Selection {
        val name = requestedName.trim()
        if (name.isEmpty()) return Selection(Status.REJECTED, requestedName, reason = "Nome de ação vazio.").also { audit(taskId, cycleId, it) }
        val tool = catalog.get(name)
        if (tool != null) {
            return Selection(Status.SELECTED, requestedName, name, tool, "Ação encontrada no Safe Android Action Catalog.").also { audit(taskId, cycleId, it) }
        }
        // A tool absent from the Android catalog may still be a normal NEXUS tool.
        // The caller must only treat SELECTED as an Android authorization candidate.
        val result = Selection(Status.NOT_ANDROID, requestedName, reason = "Ação não pertence ao catálogo Android oficial.")
        audit(taskId, cycleId, result)
        return result
    }

    fun requireAndroid(requestedName: String, taskId: String? = null, cycleId: String? = null): Selection {
        val selected = select(requestedName, taskId, cycleId)
        return if (selected.status == Status.SELECTED) selected
        else selected.copy(status = Status.REJECTED, reason = "Ação Android não registrada no catálogo seguro: ${requestedName.trim()}").also { audit(taskId, cycleId, it) }
    }

    private fun audit(taskId: String?, cycleId: String?, selection: Selection) {
        journal?.selectionEvent(taskId, selection.requestedName, selection.status.name, selection.reason, cycleId)
    }
}
