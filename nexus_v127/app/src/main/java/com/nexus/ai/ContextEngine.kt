package com.nexus.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * V1.14 Context Engine.
 * Monta um contexto operacional compacto antes do modelo planejar/executar.
 * Não concede permissões e não executa ferramentas.
 */
class ContextEngine(
    private val memory: MemoryStore,
    private val journal: AgentJournal,
    private val registry: ToolRegistry
) {
    private val goals = GoalManager(journal)

    fun build(objective: String, taskId: String? = null, backgroundExecution: Boolean = false, memoryLimit: Int = 6): NexusContext {
        val relevantMemory = MemoryRetriever(memory).relevant(objective, memoryLimit)
        val recentTasks = journal.recentTasks(5)
        val currentTask = taskId?.let { journal.findTask(it) } ?: recentTasks.firstOrNull { it.objective == objective }
        val activePlan = currentTask?.let { journal.activePlan(it.id) }
        val goal = journal.findGoalForObjective(objective)
        val goalProgress = goal?.let { goals.progress(it.id) }
        val observationStore = ObservationStore(journal)
        val observationCoordinator = ObservationCoordinator(
            observationStore,
            listOf(TaskObservationAdapter(journal), PlanCheckpointObservationAdapter(journal))
        )
        val observations = goal?.let {
            goals.reconcile(it.id, observationStore)
            observationCoordinator.snapshot(currentTask?.id ?: it.id, 12).observations + observationStore.recent(it.id, 8)
        }?.distinctBy { it.id }?.take(20) ?: currentTask?.let { observationCoordinator.snapshot(it.id, 12).observations } ?: emptyList()

        val capabilities = registry.definitions()
            .map { it.capability }
            .distinct()
            .sortedBy { it.name }
            .map { it.name }

        return NexusContext(
            objective = objective,
            taskId = currentTask?.id,
            taskState = currentTask?.state?.name,
            memory = relevantMemory,
            tools = registry.definitions().map { it.name }.distinct(),
            capabilities = capabilities,
            activePlanId = activePlan?.first,
            goalId = goal?.id,
            goalStatus = goal?.status?.name,
            goalProgress = goalProgress?.percent,
            observations = observations,
            backgroundExecution = backgroundExecution
        )
    }

    fun systemMessage(context: NexusContext): ChatMessage =
        ChatMessage(MessageRole.SYSTEM, context.toPrompt())
}

data class NexusContext(
    val objective: String,
    val taskId: String?,
    val taskState: String?,
    val memory: List<String>,
    val tools: List<String>,
    val capabilities: List<String>,
    val activePlanId: String?,
    val goalId: String?,
    val goalStatus: String?,
    val goalProgress: Int?,
    val observations: List<NexusObservation> = emptyList(),
    val backgroundExecution: Boolean
) {
    fun toPrompt(): String = buildString {
        append("Contexto operacional do NEXUS (V1.22).\n")
        append("Objetivo: ").append(objective.take(1000)).append('\n')
        taskId?.let { append("Tarefa: ").append(it).append('\n') }
        taskState?.let { append("Estado: ").append(it).append('\n') }
        activePlanId?.let { append("Plano ativo: ").append(it).append('\n') }
        goalId?.let { append("Objetivo persistente: ").append(it).append('\n') }
        goalStatus?.let { append("Estado do objetivo: ").append(it).append('\n') }
        goalProgress?.let { append("Progresso do objetivo: ").append(it).append("%\n") }
        if (observations.isNotEmpty()) {
            append("Observações verificáveis recentes:\n")
            observations.forEach { append("- [").append(it.state.name).append("] ").append(it.fact.take(300)).append(" (conf=").append("%.2f".format(it.confidence)).append(")\n") }
        }
        append("Execução em background: ").append(backgroundExecution).append('\n')
        append("Ferramentas disponíveis: ").append(tools.joinToString(", ")).append('\n')
        append("Capabilities declaradas: ").append(capabilities.joinToString(", ")).append('\n')
        if (memory.isNotEmpty()) {
            append("Memória relevante:\n")
            memory.forEach { append("- ").append(it.take(500)).append('\n') }
        }
        append("Use este contexto apenas para orientar planejamento e resposta; permissões e segurança continuam sendo decididas pelas camadas do NEXUS.")
    }

    fun toJson(): String = JSONObject().apply {
        put("objective", objective)
        put("taskId", taskId)
        put("taskState", taskState)
        put("memory", JSONArray(memory))
        put("tools", JSONArray(tools))
        put("capabilities", JSONArray(capabilities))
        put("activePlanId", activePlanId)
        put("goalId", goalId)
        put("goalStatus", goalStatus)
        put("goalProgress", goalProgress)
        put("observations", JSONArray(observations.map { it.toJson() }))
        put("backgroundExecution", backgroundExecution)
    }.toString()
}
