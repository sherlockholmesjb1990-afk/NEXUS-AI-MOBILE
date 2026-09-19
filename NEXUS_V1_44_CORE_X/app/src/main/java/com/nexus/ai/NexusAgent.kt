package com.nexus.ai

import java.util.UUID
import kotlinx.coroutines.ensureActive

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
    private val androidActionSelector: AndroidActionSelector? = null,
    private val androidEvidenceSources: List<AndroidStateEvidenceSource> = emptyList()
) {
    var state: TaskState = TaskState.PLANNING
        private set

    suspend fun run(initial: List<ChatMessage>, existingTaskId: String? = null): AgentResponse {
        val taskId = existingTaskId ?: UUID.randomUUID().toString()
        val objective = initial.lastOrNull { it.role == MessageRole.USER }?.text.orEmpty()
        val cycleTrace = NexusCycleTrace.begin(objective)
        val cycleContext = cycleTrace.context
        val goal = goalManager?.let { gm ->
            journal.findGoalForObjective(objective) ?: gm.createGoal(objective)
        }
        goal?.let { goalManager.setGoalStatus(it.id, GoalStatus.ACTIVE) }
        if (existingTaskId == null) journal.createTask(taskId, objective, cycleId = cycleContext.cycleId)
        else journal.add(taskId, "AGENT_RESUME", "Agente retomando tarefa persistida.", cycleContext.cycleId)
        if (existingTaskId == null) journal.add(taskId, "TASK_CREATED", "Nova tarefa criada.", cycleContext.cycleId)
        state = TaskState.PLANNING
        cycleTrace.stage(CycleStageName.OBSERVE, "Ciclo iniciado no agente.")
        journal.setTaskState(taskId, state, cycleContext.cycleId)

        val operationalContext = contextEngine?.build(objective, taskId, backgroundExecution)
        val effectiveInitial = if (operationalContext == null) initial else {
            initial.filterNot { it.role == MessageRole.SYSTEM && it.text.startsWith("Contexto operacional do NEXUS") } +
                ChatMessage(MessageRole.SYSTEM, operationalContext.toPrompt())
        }
        if (operationalContext != null) {
            journal.add(taskId, "CONTEXT_BUILT", "Contexto operacional montado antes da execução.", cycleContext.cycleId)
        }

        // All remote capabilities are gated before leaving the device.
        if (permissionCenter != null) {
            val network = permissionCenter.await(
                taskId, com.nexus.ai.security.PermissionKind.NETWORK,
                "Acesso à rede",
                "O NEXUS precisa usar o gateway remoto para enviar esta tarefa ao modelo."
            )
            journal.audit(taskId, "gateway", com.nexus.ai.security.PermissionKind.NETWORK, if (network is com.nexus.ai.security.PermissionGate.Result.Allowed) com.nexus.ai.security.PermissionDecision.ALLOW else com.nexus.ai.security.PermissionDecision.DENY, "Acesso à rede", cycleContext.cycleId)
            if (network !is com.nexus.ai.security.PermissionGate.Result.Allowed) {
                state = TaskState.BLOCKED
                journal.setTaskState(taskId, state, cycleContext.cycleId)
                journal.add(taskId, "BLOCKED", "Rede não autorizada.", cycleContext.cycleId)
                return AgentResponse("🚫 ${(network as com.nexus.ai.security.PermissionGate.Result.Denied).reason}")
            }
            if (webSearch) {
                val web = permissionCenter.await(
                    taskId, com.nexus.ai.security.PermissionKind.WEB_SEARCH,
                    "Pesquisa na Web",
                    "O NEXUS poderá consultar fontes públicas na Web para esta tarefa."
                )
                journal.audit(taskId, "web_search", com.nexus.ai.security.PermissionKind.WEB_SEARCH, if (web is com.nexus.ai.security.PermissionGate.Result.Allowed) com.nexus.ai.security.PermissionDecision.ALLOW else com.nexus.ai.security.PermissionDecision.DENY, "Pesquisa na Web", cycleContext.cycleId)
                if (web !is com.nexus.ai.security.PermissionGate.Result.Allowed) {
                    state = TaskState.BLOCKED
                    journal.setTaskState(taskId, state, cycleContext.cycleId)
                    journal.add(taskId, "BLOCKED", "Pesquisa na Web não autorizada.", cycleContext.cycleId)
                    return AgentResponse("🚫 ${(web as com.nexus.ai.security.PermissionGate.Result.Denied).reason}")
                }
            }
        }

        // V1.13 recovery: if a persisted plan is still RUNNING/CREATED, resume its remaining steps
        // instead of asking the model to recreate the plan. The saved responseId is the continuation anchor.
        val persistedPlan = if (existingTaskId != null) journal.activePlan(taskId, cycleContext.cycleId) else null
        var response = if (persistedPlan != null) {
            val savedResponseId = journal.cycleResumeAnchor(taskId, cycleContext.cycleId)?.responseId
                ?: journal.findTask(taskId)?.let { taskIdValue ->
                    // Backward-compatible fallback for pre-V1.43 data.
                    journal.checkpointResponseId(taskIdValue.id, cycleContext.cycleId)
                }
            if (savedResponseId.isNullOrBlank()) {
                provider.respond(effectiveInitial, registry.definitions(), webSearch)
            } else {
                val plan = planner.fromJson(persistedPlan.second)
                journal.add(taskId, "RECOVERY_PLAN", "Retomando plano ${plan.id} a partir dos checkpoints persistidos.", cycleContext.cycleId)
                state = TaskState.VERIFYING
                journal.setTaskState(taskId, state, cycleContext.cycleId)
                val execution = MultiStepExecution(registry, verifier, journal, taskId, cycleContext.cycleId, backgroundExecution, permissionCenter, 1, androidStateObservationAdapter, androidActionSelector = androidActionSelector, androidEvidenceSources = androidEvidenceSources).execute(plan)
                when (execution) {
                    is ExecutionReport.Success -> {
                        journal.updatePlanStatus(plan.id, "EXECUTED")
                        provider.continueWithToolResults(savedResponseId, execution.results, registry.definitions(), webSearch)
                    }
                    is ExecutionReport.Blocked -> { state = TaskState.BLOCKED; cycleTrace.stage(CycleStageName.POLICY, execution.message); cycleTrace.finish(CycleResult.BLOCKED); journal.setTaskState(taskId, state, cycleContext.cycleId); return AgentResponse("🚫 ${execution.message}") }
                    is ExecutionReport.Failed -> { state = TaskState.FAILED; cycleTrace.stage(CycleStageName.ACT, execution.message); cycleTrace.finish(CycleResult.FAILED); journal.setTaskState(taskId, state, cycleContext.cycleId); return AgentResponse(execution.message) }
                    is ExecutionReport.Unknown -> { state = TaskState.FAILED; cycleTrace.stage(CycleStageName.VERIFY, execution.message); cycleTrace.finish(CycleResult.UNKNOWN); journal.setTaskState(taskId, state, cycleContext.cycleId); return AgentResponse(execution.message) }
                }
            }
        } else try {
            provider.respond(effectiveInitial, registry.definitions(), webSearch)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            state = TaskState.FAILED
            journal.setTaskState(taskId, state, cycleContext.cycleId)
            journal.add(taskId, "FAILED", e.message ?: "Erro no provedor.", cycleContext.cycleId)
            return AgentResponse("Falha no provedor: ${e.message}")
        }

        val agentLoop = AgentLoop(maxSteps, journal, taskId, cycleContext.cycleId)
        val observationCoordinator = ObservationCoordinator(
            ObservationStore(journal),
            listOf(TaskObservationAdapter(journal), PlanCheckpointObservationAdapter(journal)) + listOfNotNull(androidStateObservationAdapter)
        )
        while (agentLoop.hasNext()) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val cycleObservation = observationCoordinator.snapshot(taskId, 20, cycleContext.cycleId)
            val cycle = agentLoop.beginObservation(cycleObservation)
            journal.add(taskId, "MODEL_RESPONSE", "Etapa ${cycle.iteration}; toolCalls=${response.toolCalls.size}", cycleContext.cycleId)
            agentLoop.decide("Modelo respondeu com ${response.toolCalls.size} chamada(s); observações foram consultadas antes da decisão.")

            if (response.toolCalls.isEmpty()) {
                state = TaskState.COMPLETED
                cycleTrace.stage(CycleStageName.COMPLETE, "Execução concluída sem novas ferramentas.")
                cycleTrace.finish(CycleResult.SUCCESS)
                agentLoop.complete("Modelo não solicitou novas ferramentas.")
                goal?.let { g -> goalManager?.setGoalStatus(g.id, GoalStatus.COMPLETED) }
                journal.setTaskState(taskId, state, cycleContext.cycleId)
                journal.add(taskId, "COMPLETED", response.text, cycleContext.cycleId)
                return response
            }

            // V1.12: o agente cria um plano explícito antes de executar qualquer tool call.
            state = TaskState.PLANNING
            agentLoop.act("Plano será criado e executado pelas camadas de segurança existentes.")
            journal.setTaskState(taskId, state, cycleContext.cycleId)
            if (androidActionSelector != null) {
                response.toolCalls.filter { registry.get(it.name) is AndroidActionAdapter }.forEach { call ->
                    val selection = androidActionSelector.requireAndroid(call.name, taskId, cycleContext.cycleId)
                    if (selection.status != AndroidActionSelector.Status.SELECTED) {
                        state = TaskState.BLOCKED
                        agentLoop.blocked("Ação Android ${call.name} rejeitada antes do planejamento: ${selection.reason}")
                        journal.setTaskState(taskId, state, cycleContext.cycleId)
                        return AgentResponse("🚫 Ação Android ${call.name} rejeitada pelo catálogo seguro.")
                    }
                }
            }
            val plan = planner.createPlan(objective, response.toolCalls, registry)
            journal.savePlan(taskId, plan, planner.toJson(plan), cycleContext.cycleId)
            goal?.let { g ->
                response.toolCalls.forEachIndexed { index, call ->
                    goalManager?.addTask(g.id, "Executar ${call.name}", index + 1)
                }
            }
            journal.updatePlanStatus(plan.id, "RUNNING")
            journal.checkpoint(taskId, response.responseId, "Plano ${plan.id} persistido antes da execução.", cycleContext.cycleId)
            journal.add(taskId, "PLAN_CREATED", "Plano ${plan.id} criado com ${plan.steps.size} etapa(s).", cycleContext.cycleId)

            state = TaskState.VERIFYING
            journal.setTaskState(taskId, state, cycleContext.cycleId)
            agentLoop.verify("Execução multi-etapas concluída; resultado será reconciliado com verificação explícita.")

            val executor = MultiStepExecution(
                registry = registry,
                verifier = verifier,
                journal = journal,
                taskId = taskId,
                cycleId = cycleContext.cycleId,
                backgroundExecution = backgroundExecution,
                permissionCenter = permissionCenter,
                maxRetriesPerStep = 1,
                androidStateObservationAdapter = androidStateObservationAdapter,
                androidActionSelector = androidActionSelector,
                androidEvidenceSources = androidEvidenceSources
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
                    journal.setTaskState(taskId, state, cycleContext.cycleId)
                    return AgentResponse(execution.message)
                }
                is ExecutionReport.Blocked -> {
                    goal?.let { g -> goalManager?.setGoalStatus(g.id, GoalStatus.BLOCKED) }
                    state = TaskState.BLOCKED
                    agentLoop.blocked(execution.message)
                    journal.setTaskState(taskId, state, cycleContext.cycleId)
                    return AgentResponse("🚫 ${execution.message}")
                }
                is ExecutionReport.Unknown -> {
                    agentLoop.recover("Estado UNKNOWN: recuperação/encerramento seguro necessário.")
                    goal?.let { g -> goalManager?.setGoalStatus(g.id, GoalStatus.BLOCKED) }
                    state = TaskState.FAILED
                    agentLoop.failed(execution.message)
                    journal.setTaskState(taskId, state, cycleContext.cycleId)
                    return AgentResponse(execution.message)
                }
            }

            journal.add(taskId, "PLAN_EXECUTED", "Plano ${plan.id} executado com ${results.size}/${plan.steps.size} etapa(s) confirmada(s); aguardando continuação do modelo.", cycleContext.cycleId)
            agentLoop.observeAgain("Resultados verificados serão usados como evidência para a próxima decisão.")

            val responseId = response.responseId
            if (responseId == null) {
                state = TaskState.FAILED
                journal.add(taskId, "FAILED", "Resposta sem response_id para continuação.", cycleContext.cycleId)
                return AgentResponse("O modelo solicitou uma ferramenta, mas não forneceu um identificador de continuação.")
            }

            state = TaskState.EXECUTING
            journal.setTaskState(taskId, state, cycleContext.cycleId)
            response = try {
                provider.continueWithToolResults(
                    responseId,
                    results,
                    registry.definitions(),
                    webSearch
                )
            } catch (e: Exception) {
                state = TaskState.FAILED
                journal.add(taskId, "FAILED", e.message ?: "Erro na continuação.", cycleContext.cycleId)
                if (e is kotlinx.coroutines.CancellationException) throw e
                return AgentResponse("Falha ao continuar a tarefa: ${e.message}")
            }
            journal.checkpoint(taskId, response.responseId, "Etapa de execução concluída; continuação disponível.", cycleContext.cycleId)
            journal.add(taskId, "CONTINUED", "Resultados enviados ao modelo.", cycleContext.cycleId)
        }

        agentLoop.failed("Limite de iterações do agente atingido.")
        state = TaskState.FAILED
        journal.setTaskState(taskId, state, cycleContext.cycleId)
        journal.add(taskId, "FAILED", "Limite de etapas atingido.", cycleContext.cycleId)
        return AgentResponse("O NEXUS interrompeu a tarefa para evitar um ciclo infinito.")
    }
}
