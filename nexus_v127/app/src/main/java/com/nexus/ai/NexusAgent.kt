package com.nexus.ai

import java.util.UUID

class NexusAgent(
    private val provider: AIProvider,
    private val registry: ToolRegistry,
    private val journal: AgentJournal,
    private val verifier: NexusVerifier = NexusVerifier(),
    private val permissionCenter: com.nexus.ai.security.PermissionCenter? = null,
    private val maxSteps: Int = 5,
    private val webSearch: Boolean = false,
    private val backgroundExecution: Boolean = false,
    private val planner: NexusPlanner = NexusPlanner(),
    private val contextEngine: ContextEngine? = null,
    private val goalManager: GoalManager? = null,
    private val androidStateObservationAdapter: ObservationAdapter? = null,
    private val androidActionSelector: AndroidActionSelector? = null
) {
    var state: TaskState = TaskState.PLANNING
        private set

    suspend fun run(initial: List<ChatMessage>, existingTaskId: String? = null): AgentResponse {
        val taskId = existingTaskId ?: UUID.randomUUID().toString()
        val objective = initial.lastOrNull { it.role == MessageRole.USER }?.text.orEmpty()
        val goal = goalManager?.let { gm ->
            journal.findGoalForObjective(objective) ?: gm.createGoal(objective)
        }
        goal?.let { goalManager.setGoalStatus(it.id, GoalStatus.ACTIVE) }
        if (existingTaskId == null) journal.createTask(taskId, objective)
        else journal.add(taskId, "AGENT_RESUME", "Agente retomando tarefa persistida.")
        if (existingTaskId == null) journal.add(taskId, "TASK_CREATED", "Nova tarefa criada.")
        state = TaskState.PLANNING
        journal.setTaskState(taskId, state)

        val operationalContext = contextEngine?.build(objective, taskId, backgroundExecution)
        val effectiveInitial = if (operationalContext.isNullOrBlank()) initial else {
            initial.filterNot { it.role == MessageRole.SYSTEM && it.text.startsWith("Contexto operacional do NEXUS") } +
                ChatMessage(MessageRole.SYSTEM, operationalContext)
        }
        if (!operationalContext.isNullOrBlank()) {
            journal.add(taskId, "CONTEXT_BUILT", "Contexto operacional montado antes da execução.")
        }

        // All remote capabilities are gated before leaving the device.
        if (permissionCenter != null) {
            val network = permissionCenter.await(
                taskId, com.nexus.ai.security.PermissionKind.NETWORK,
                "Acesso à rede",
                "O NEXUS precisa usar o gateway remoto para enviar esta tarefa ao modelo."
            )
            journal.audit(taskId, "gateway", com.nexus.ai.security.PermissionKind.NETWORK, if (network is com.nexus.ai.security.PermissionGate.Result.Allowed) com.nexus.ai.security.PermissionDecision.ALLOW else com.nexus.ai.security.PermissionDecision.DENY, "Acesso à rede")
            if (network !is com.nexus.ai.security.PermissionGate.Result.Allowed) {
                state = TaskState.BLOCKED
                journal.setTaskState(taskId, state)
                journal.add(taskId, "BLOCKED", "Rede não autorizada.")
                return AgentResponse("🚫 ${(network as com.nexus.ai.security.PermissionGate.Result.Denied).reason}")
            }
            if (webSearch) {
                val web = permissionCenter.await(
                    taskId, com.nexus.ai.security.PermissionKind.WEB_SEARCH,
                    "Pesquisa na Web",
                    "O NEXUS poderá consultar fontes públicas na Web para esta tarefa."
                )
                journal.audit(taskId, "web_search", com.nexus.ai.security.PermissionKind.WEB_SEARCH, if (web is com.nexus.ai.security.PermissionGate.Result.Allowed) com.nexus.ai.security.PermissionDecision.ALLOW else com.nexus.ai.security.PermissionDecision.DENY, "Pesquisa na Web")
                if (web !is com.nexus.ai.security.PermissionGate.Result.Allowed) {
                    state = TaskState.BLOCKED
                    journal.setTaskState(taskId, state)
                    journal.add(taskId, "BLOCKED", "Pesquisa na Web não autorizada.")
                    return AgentResponse("🚫 ${(web as com.nexus.ai.security.PermissionGate.Result.Denied).reason}")
                }
            }
        }

        // V1.13 recovery: if a persisted plan is still RUNNING/CREATED, resume its remaining steps
        // instead of asking the model to recreate the plan. The saved responseId is the continuation anchor.
        val persistedPlan = if (existingTaskId != null) journal.activePlan(taskId) else null
        var response = if (persistedPlan != null) {
            val savedResponseId = journal.findTask(taskId)?.let { taskIdValue ->
                // responseId is stored in the task checkpoint column; read through the journal helper below.
                journal.checkpointResponseId(taskIdValue.id)
            }
            if (savedResponseId.isNullOrBlank()) {
                provider.respond(effectiveInitial, registry.definitions(), webSearch)
            } else {
                val plan = planner.fromJson(persistedPlan.second)
                journal.add(taskId, "RECOVERY_PLAN", "Retomando plano ${plan.id} a partir dos checkpoints persistidos.")
                state = TaskState.VERIFYING
                journal.setTaskState(taskId, state)
                val execution = MultiStepExecution(registry, verifier, journal, taskId, backgroundExecution, permissionCenter, 1, androidStateObservationAdapter, androidActionSelector = androidActionSelector).execute(plan)
                when (execution) {
                    is ExecutionReport.Success -> {
                        journal.updatePlanStatus(plan.id, "EXECUTED")
                        provider.continueWithToolResults(savedResponseId, execution.results, registry.definitions(), webSearch)
                    }
                    is ExecutionReport.Blocked -> { state = TaskState.BLOCKED; journal.setTaskState(taskId, state); return AgentResponse("🚫 ${execution.message}") }
                    is ExecutionReport.Failed -> { state = TaskState.FAILED; journal.setTaskState(taskId, state); return AgentResponse(execution.message) }
                    is ExecutionReport.Unknown -> { state = TaskState.FAILED; journal.setTaskState(taskId, state); return AgentResponse(execution.message) }
                }
            }
        } else try {
            provider.respond(effectiveInitial, registry.definitions(), webSearch)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            state = TaskState.FAILED
            journal.setTaskState(taskId, state)
            journal.add(taskId, "FAILED", e.message ?: "Erro no provedor.")
            return AgentResponse("Falha no provedor: ${e.message}")
        }

        val agentLoop = AgentLoop(maxSteps, journal, taskId)
        val observationCoordinator = ObservationCoordinator(
            ObservationStore(journal),
            listOf(TaskObservationAdapter(journal), PlanCheckpointObservationAdapter(journal)) + listOfNotNull(androidStateObservationAdapter)
        )
        while (agentLoop.hasNext()) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val cycleObservation = observationCoordinator.snapshot(taskId, 20)
            val cycle = agentLoop.beginObservation(cycleObservation)
            journal.add(taskId, "MODEL_RESPONSE", "Etapa ${cycle.iteration}; toolCalls=${response.toolCalls.size}")
            agentLoop.decide("Modelo respondeu com ${response.toolCalls.size} chamada(s); observações foram consultadas antes da decisão.")

            if (response.toolCalls.isEmpty()) {
                state = TaskState.COMPLETED
                agentLoop.complete("Modelo não solicitou novas ferramentas.")
                goal?.let { g -> goalManager?.setGoalStatus(g.id, GoalStatus.COMPLETED) }
                journal.setTaskState(taskId, state)
                journal.add(taskId, "COMPLETED", response.text)
                return response
            }

            // V1.12: o agente cria um plano explícito antes de executar qualquer tool call.
            state = TaskState.PLANNING
            agentLoop.act("Plano será criado e executado pelas camadas de segurança existentes.")
            journal.setTaskState(taskId, state)
            if (androidActionSelector != null) {
                response.toolCalls.filter { registry.get(it.name) is AndroidActionAdapter }.forEach { call ->
                    val selection = androidActionSelector.requireAndroid(call.name, taskId)
                    if (selection.status != AndroidActionSelector.Status.SELECTED) {
                        state = TaskState.BLOCKED
                        agentLoop.blocked("Ação Android ${call.name} rejeitada antes do planejamento: ${selection.reason}")
                        journal.setTaskState(taskId, state)
                        return AgentResponse("🚫 Ação Android ${call.name} rejeitada pelo catálogo seguro.")
                    }
                }
            }
            val plan = planner.createPlan(objective, response.toolCalls, registry)
            journal.savePlan(taskId, plan, planner.toJson(plan))
            goal?.let { g ->
                response.toolCalls.forEachIndexed { index, call ->
                    goalManager?.addTask(g.id, "Executar ${call.name}", index + 1)
                }
            }
            journal.updatePlanStatus(plan.id, "RUNNING")
            journal.checkpoint(taskId, response.responseId, "Plano ${plan.id} persistido antes da execução.")
            journal.add(taskId, "PLAN_CREATED", "Plano ${plan.id} criado com ${plan.steps.size} etapa(s).")

            state = TaskState.VERIFYING
            journal.setTaskState(taskId, state)
            agentLoop.verify("Execução multi-etapas concluída; resultado será reconciliado com verificação explícita.")

            val executor = MultiStepExecution(
                registry = registry,
                verifier = verifier,
                journal = journal,
                taskId = taskId,
                backgroundExecution = backgroundExecution,
                permissionCenter = permissionCenter,
                maxRetriesPerStep = 1,
                androidStateObservationAdapter = androidStateObservationAdapter,
                androidActionSelector = androidActionSelector
            )
            val execution = executor.execute(plan)
            val results = when (execution) {
                is ExecutionReport.Success -> {
                    goal?.let { g -> goalManager?.setGoalStatus(g.id, GoalStatus.ACTIVE) }
                    execution.results
                }
                is ExecutionReport.Failed -> {
                    agentLoop.recover("Falha de execução: Recovery Engine/estado seguro foi acionado.")
                    goal?.let { g -> goalManager?.setGoalStatus(g.id, GoalStatus.FAILED) }
                    state = TaskState.FAILED
                    agentLoop.failed(execution.message)
                    journal.setTaskState(taskId, state)
                    return AgentResponse(execution.message)
                }
                is ExecutionReport.Blocked -> {
                    goal?.let { g -> goalManager?.setGoalStatus(g.id, GoalStatus.BLOCKED) }
                    state = TaskState.BLOCKED
                    agentLoop.blocked(execution.message)
                    journal.setTaskState(taskId, state)
                    return AgentResponse("🚫 ${execution.message}")
                }
                is ExecutionReport.Unknown -> {
                    agentLoop.recover("Estado UNKNOWN: recuperação/encerramento seguro necessário.")
                    goal?.let { g -> goalManager?.setGoalStatus(g.id, GoalStatus.BLOCKED) }
                    state = TaskState.FAILED
                    agentLoop.failed(execution.message)
                    journal.setTaskState(taskId, state)
                    return AgentResponse(execution.message)
                }
            }

            journal.add(taskId, "PLAN_EXECUTED", "Plano ${plan.id} executado com ${results.size}/${plan.steps.size} etapa(s) confirmada(s); aguardando continuação do modelo.")
            agentLoop.observeAgain("Resultados verificados serão usados como evidência para a próxima decisão.")

            val responseId = response.responseId
            if (responseId == null) {
                state = TaskState.FAILED
                journal.add(taskId, "FAILED", "Resposta sem response_id para continuação.")
                return AgentResponse("O modelo solicitou uma ferramenta, mas não forneceu um identificador de continuação.")
            }

            state = TaskState.EXECUTING
            journal.setTaskState(taskId, state)
            response = try {
                provider.continueWithToolResults(
                    responseId,
                    results,
                    registry.definitions(),
                    webSearch
                )
            } catch (e: Exception) {
                state = TaskState.FAILED
                journal.add(taskId, "FAILED", e.message ?: "Erro na continuação.")
                if (e is kotlinx.coroutines.CancellationException) throw e
                return AgentResponse("Falha ao continuar a tarefa: ${e.message}")
            }
            journal.checkpoint(taskId, response.responseId, "Etapa ${step + 1} concluída")
            journal.add(taskId, "CONTINUED", "Resultados enviados ao modelo.")
        }

        agentLoop.failed("Limite de iterações do agente atingido.")
        state = TaskState.FAILED
        journal.setTaskState(taskId, state)
        journal.add(taskId, "FAILED", "Limite de etapas atingido.")
        return AgentResponse("O NEXUS interrompeu a tarefa para evitar um ciclo infinito.")
    }
}
