package com.nexus.ai

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * V1.13: executes an already-approved NexusPlan sequentially.
 * It does not bypass verifier, capability or permission layers.
 * Retries are deliberately conservative: only execution exceptions may retry.
 */
class MultiStepExecution(
    private val registry: ToolRegistry,
    private val verifier: NexusVerifier,
    private val journal: AgentJournal,
    private val taskId: String,
    private val cycleId: String? = null,
    private val backgroundExecution: Boolean,
    private val permissionCenter: com.nexus.ai.security.PermissionCenter?,
    private val maxRetriesPerStep: Int = 1,
    private val androidStateObservationAdapter: ObservationAdapter? = null,
    private val contractEvaluator: AndroidActionContractEvaluator = AndroidActionContractEvaluator(),
    private val recoveryEngine: NexusRecoveryEngine = NexusRecoveryEngine(journal, taskId, cycleId, maxRetriesPerStep),
    private val observationCoordinator: ObservationCoordinator = ObservationCoordinator(
        ObservationStore(journal),
        listOf(TaskObservationAdapter(journal), PlanCheckpointObservationAdapter(journal)) + listOfNotNull(androidStateObservationAdapter)
    ),
    private val androidActionSelector: AndroidActionSelector? = null,
    private val androidEvidenceSources: List<AndroidStateEvidenceSource> = emptyList(),
    private val androidActionResultGateway: AndroidActionResultGateway = AndroidActionResultGateway()
) {
    private fun log(type: String, detail: String) = journal.add(taskId, type, detail, cycleId)

    suspend fun execute(plan: NexusPlan): ExecutionReport {
        val results = mutableListOf<ToolResult>()
        val completed = mutableSetOf<String>()

        for (step in plan.steps.sortedBy { it.order }) {
            currentCoroutineContext().ensureActive()
            val persistedState = journal.planStepState(step.id)
            if (persistedState?.first == "CONFIRMED") {
                // Never execute a confirmed step again during recovery.
                results += ToolResult(step.toolCallId, step.toolName, persistedState.second, true)
                completed += step.id
                log("STEP_RESUMED", "Etapa ${step.order} já confirmada; execução ignorada durante retomada.")
                continue
            }
            if (persistedState?.first == "RUNNING") {
                // A crash may have happened after execute() but before verification.
                // Re-running could duplicate a side effect, so fail closed.
                fail(plan, step, "Estado RUNNING sem confirmação; reexecução bloqueada para evitar duplicidade.")
                return ExecutionReport.Unknown("Etapa ${step.order} ficou em estado indeterminado e não será repetida automaticamente.", results)
            }

            val missing = step.dependencies.filter { it !in completed }
            if (missing.isNotEmpty()) {
                fail(plan, step, "Dependências não concluídas: ${missing.joinToString()}")
                return ExecutionReport.Failed("Dependências da etapa ${step.order} não foram concluídas.", results)
            }

            val call = ToolCall(step.toolCallId, step.toolName, step.argumentsJson)
            val registeredTool = registry.get(call.name)
            if (registeredTool == null) {
                fail(plan, step, "Ferramenta não registrada.")
                return ExecutionReport.Failed("Ferramenta desconhecida: ${call.name}", results)
            }
            val tool = if (registeredTool is AndroidActionAdapter && androidActionSelector != null) {
                val selection = androidActionSelector.requireAndroid(call.name, taskId, cycleId)
                if (selection.status != AndroidActionSelector.Status.SELECTED || selection.tool == null) {
                    block(plan, step, selection.reason)
                    return ExecutionReport.Blocked("Ação Android ${call.name} rejeitada pelo catálogo seguro.", results)
                }
                selection.tool
            } else registeredTool

            // V1.25: deterministic Android precondition gate runs before authorization.
            if (tool is AndroidActionAdapter) {
                val preObservation = observationCoordinator.snapshot(taskId, 20, cycleId)
                val precheck = contractEvaluator.precheck(tool.actionContract(), backgroundExecution, preObservation.observations)
                journal.contractEvent(taskId, precheck, cycleId)
                if (precheck.status != ContractStatus.ALLOW) {
                    when (precheck.status) {
                        ContractStatus.BLOCK -> block(plan, step, precheck.detail)
                        ContractStatus.UNKNOWN -> fail(plan, step, "Precondition inconclusiva: ${precheck.detail}")
                        else -> fail(plan, step, precheck.detail)
                    }
                    return if (precheck.status == ContractStatus.BLOCK) ExecutionReport.Blocked("Ação ${call.name} bloqueada pela precondition.", results)
                    else ExecutionReport.Unknown("Precondition inconclusiva na etapa ${step.order}.", results)
                }
            }

            val decision = verifier.verify(tool.definition, backgroundExecution)
            log("VERIFIER", "${call.name}: ${decision.reason}")
            val capabilitySpec = com.nexus.ai.security.CapabilityPolicy.spec(tool.definition.capability)
            log("CAPABILITY_CHECK", "${call.name}: capability=${tool.definition.capability.name}; risk=${capabilitySpec.risk.name}; background=${capabilitySpec.allowedInBackground}")

            if (backgroundExecution && !capabilitySpec.allowedInBackground) {
                block(plan, step, "Capability não permitida em background.")
                return ExecutionReport.Blocked("Ação ${call.name} bloqueada em background.", results)
            }
            if (!decision.allowed) {
                block(plan, step, decision.reason)
                return ExecutionReport.Blocked("Ação bloqueada pelo verificador: ${decision.reason}", results)
            }
            if (decision.needsConfirmation && permissionCenter == null) {
                block(plan, step, "PermissionCenter ausente para ação que exige aprovação.")
                return ExecutionReport.Blocked("${call.name} exige autorização explícita.", results)
            }
            log("CONTRACT_AUTHORIZE", "${call.name}: preconditions aprovadas; entrando na autorização.")
            if (permissionCenter != null) {
                val permission = permissionCenter.await(
                    taskId, tool.definition.permission,
                    "Ferramenta: ${call.name}",
                    "O NEXUS solicitou a execução de ${call.name}. Capability: ${tool.definition.capability.name}."
                )
                journal.audit(taskId, call.name, tool.definition.permission,
                    if (permission is com.nexus.ai.security.PermissionGate.Result.Allowed) com.nexus.ai.security.PermissionDecision.ALLOW else com.nexus.ai.security.PermissionDecision.DENY,
                    call.argumentsJson, cycleId)
                if (permission !is com.nexus.ai.security.PermissionGate.Result.Allowed) {
                    block(plan, step, "Permissão negada.")
                    return ExecutionReport.Blocked("Ferramenta ${call.name} não autorizada.", results)
                }
            }

            log("ACTION_EXECUTE", "${call.name}: contrato autorizado; execução iniciada.")
            journal.updatePlanStep(step.id, "RUNNING", "Execução iniciada; dependências satisfeitas.")
            var attempt = 0
            var executionFailure: String? = null
            var result: ToolResult? = null
            var activeTool = tool
            var activeCall = call

            while (true) {
                currentCoroutineContext().ensureActive()
                attempt++
                log("STEP_ATTEMPT", "Etapa ${step.order}: tentativa $attempt.")
                result = try {
                    activeTool.execute(activeCall.argumentsJson).also { executionFailure = null }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    executionFailure = e.message ?: "Erro desconhecido na execução."
                    ToolResult(activeCall.id, activeCall.name, "Falha durante a execução: $executionFailure", false)
                }
                log("TOOL_RESULT", "${activeCall.name}: ${result!!.output}")
                if (activeTool is AndroidActionAdapter) {
                    val normalized = androidActionResultGateway.normalize(activeCall.name, result!!, cycleId = cycleId)
                    journal.actionResultEvent(taskId, normalized)
                }

                if (result!!.success) break

                val failureType = recoveryEngine.classify(Exception(executionFailure ?: result!!.output))
                val decision = recoveryEngine.decide(activeCall.name, failureType, attempt)
                when (decision.action) {
                    RecoveryAction.RETRY -> {
                        recoveryEngine.waitBeforeRetry(decision)
                        continue
                    }
                    RecoveryAction.FALLBACK -> {
                        val fallbackName = decision.fallbackTool
                        val fallback = fallbackName?.let { registry.get(it) }
                        if (fallback == null) {
                            fail(plan, step, "Fallback ${decision.fallbackTool} não está disponível.")
                            return ExecutionReport.Failed("Fallback indisponível na etapa ${step.order}.", results)
                        }
                        val safeFallback = if (fallback is AndroidActionAdapter && androidActionSelector != null) {
                            val selection = androidActionSelector.requireAndroid(fallback.definition.name, taskId, cycleId)
                            if (selection.status != AndroidActionSelector.Status.SELECTED || selection.tool == null) {
                                fail(plan, step, "Fallback Android rejeitado pelo catálogo seguro.")
                                return ExecutionReport.Blocked("Fallback Android rejeitado pelo catálogo seguro.", results)
                            }
                            selection.tool
                        } else fallback
                        val fallbackDecision = verifier.verify(safeFallback.definition, backgroundExecution)
                        log("RECOVERY_FALLBACK_VERIFY", "${fallback.definition.name}: ${fallbackDecision.reason}")
                        if (!fallbackDecision.allowed || (backgroundExecution && !com.nexus.ai.security.CapabilityPolicy.spec(fallback.definition.capability).allowedInBackground)) {
                            fail(plan, step, "Fallback bloqueado pela política de segurança.")
                            return ExecutionReport.Blocked("Fallback bloqueado na etapa ${step.order}.", results)
                        }
                        if (permissionCenter != null) {
                            val fallbackPermission = permissionCenter.await(
                                taskId, safeFallback.definition.permission,
                                "Fallback: ${safeFallback.definition.name}",
                                "O NEXUS propôs ${safeFallback.definition.name} como fallback controlado."
                            )
                            journal.audit(taskId, safeFallback.definition.name, safeFallback.definition.permission,
                                if (fallbackPermission is com.nexus.ai.security.PermissionGate.Result.Allowed) com.nexus.ai.security.PermissionDecision.ALLOW else com.nexus.ai.security.PermissionDecision.DENY,
                                "recovery_fallback", cycleId)
                            if (fallbackPermission !is com.nexus.ai.security.PermissionGate.Result.Allowed) {
                                fail(plan, step, "Fallback não autorizado.")
                                return ExecutionReport.Blocked("Fallback não autorizado na etapa ${step.order}.", results)
                            }
                        } else if (!safeFallback.definition.readOnly) {
                            fail(plan, step, "Fallback mutável exige PermissionCenter.")
                            return ExecutionReport.Blocked("Fallback mutável sem PermissionCenter.", results)
                        }
                        activeTool = safeFallback
                        activeCall = ToolCall(call.id, safeFallback.definition.name, call.argumentsJson)
                        continue
                    }
                    RecoveryAction.REQUEST_CONFIRMATION, RecoveryAction.STOP -> {
                        fail(plan, step, decision.reason)
                        return ExecutionReport.Failed("Recovery interrompeu a etapa ${step.order}: ${decision.reason}", results)
                    }
                }
            }

            val finalResult = result ?: ToolResult(activeCall.id, activeCall.name, "Nenhum resultado.", false)
            if (!finalResult.success) {
                val detail = executionFailure ?: finalResult.output
                journal.executionAudit(taskId, call.id, call.name, VerificationResult(VerificationStatus.FAILED, detail), cycleId)
                fail(plan, step, "Execução falhou após ${attempt} tentativa(s): $detail")
                return ExecutionReport.Failed("Etapa ${step.order} falhou após ${attempt} tentativa(s).", results)
            }

            log("ACTION_EVIDENCE", "${activeCall.name}: coletando evidência Android independente.")
            val independentEvidence = if (activeTool is AndroidActionAdapter) {
                androidEvidenceSources.flatMap { source ->
                    runCatching { source.observe(activeCall.name, activeCall.argumentsJson, finalResult) }
                        .getOrElse { listOf(AndroidStateEvidence(source.source, "android:evidence", "Fonte de evidência falhou: ${it.message ?: "erro desconhecido"}.", 0.0)) }
                }
            } else emptyList()
            if (independentEvidence.isNotEmpty()) {
                independentEvidence.forEach { journal.actionEvidenceEvent(taskId, activeCall.name, it.copy(cycleId = cycleId)) }
                log("ACTION_EVIDENCE_COLLECTED", independentEvidence.joinToString(" | ") { "${it.source}: ${it.fact}" })
            }
            log("ACTION_VERIFY", "${activeCall.name}: executando verificação pós-ação.")
            val verification = if (activeTool is AndroidActionAdapter) {
                activeTool.verifyResultWithEvidence(activeCall.argumentsJson, finalResult, independentEvidence)
            } else {
                activeTool.verifyResult(activeCall.argumentsJson, finalResult)
            }
            journal.executionAudit(taskId, activeCall.id, activeCall.name, verification, cycleId)
            log("POST_EXECUTION_VERIFICATION", "${activeCall.name}: ${verification.status} — ${verification.detail}")
            if (activeTool is AndroidActionAdapter) {
                val normalized = androidActionResultGateway.normalize(activeCall.name, finalResult, verification, cycleId)
                journal.actionResultEvent(taskId, normalized)
            }
            // V1.22: publish observation only from an explicit verification result.
            observationCoordinator.recordVerifiedOutcome(taskId, activeCall.name, finalResult, verification, cycleId)
            log("ACTION_OBSERVE", "${activeCall.name}: observação derivada da verificação registrada.")

            // V1.25: Android postconditions are evaluated from explicit verification evidence
            // plus the observation snapshot; tool output alone can never satisfy the contract.
            if (activeTool is AndroidActionAdapter) {
                val postObservation = observationCoordinator.snapshot(taskId, 20, cycleId)
                val postcheck = contractEvaluator.postcheck(activeTool.actionContract(), verification, postObservation.observations)
                journal.contractEvent(taskId, postcheck, cycleId)
                if (postcheck.status != ContractStatus.CONFIRMED) {
                    when (postcheck.status) {
                        ContractStatus.FAILED -> {
                            recoveryEngine.decide(activeCall.name, RecoveryFailureType.VERIFICATION_FAILED, attempt)
                            fail(plan, step, postcheck.detail)
                            return ExecutionReport.Failed("Pós-condição falhou na etapa ${step.order}.", results)
                        }
                        ContractStatus.UNKNOWN -> {
                            recoveryEngine.decide(activeCall.name, RecoveryFailureType.UNKNOWN_STATE, attempt)
                            fail(plan, step, postcheck.detail)
                            return ExecutionReport.Unknown("Pós-condição inconclusiva na etapa ${step.order}.", results)
                        }
                        else -> {
                            fail(plan, step, postcheck.detail)
                            return ExecutionReport.Failed("Contrato não confirmado na etapa ${step.order}.", results)
                        }
                    }
                }
            }

            when (verification.status) {
                VerificationStatus.FAILED -> {
                    recoveryEngine.decide(activeCall.name, RecoveryFailureType.VERIFICATION_FAILED, attempt)
                    fail(plan, step, verification.detail)
                    return ExecutionReport.Failed("Verificação falhou na etapa ${step.order}.", results)
                }
                VerificationStatus.UNKNOWN -> {
                    recoveryEngine.decide(activeCall.name, RecoveryFailureType.UNKNOWN_STATE, attempt)
                    fail(plan, step, "Resultado não pôde ser confirmado: ${verification.detail}")
                    return ExecutionReport.Unknown("Verificação inconclusiva na etapa ${step.order}.", results)
                }
                VerificationStatus.CONFIRMED -> {
                    journal.updatePlanStep(step.id, "CONFIRMED", verification.detail, finalResult.output)
                    journal.checkpoint(taskId, null, "Etapa ${step.order} concluída; checkpoint persistido.", cycleId)
                    log("CHECKPOINT", "Etapa ${step.order}/${plan.steps.size} CONFIRMED.")
                    completed += step.id
                    results += finalResult
                }
            }
        }

        journal.updatePlanStatus(plan.id, "COMPLETED")
        log("PLAN_COMPLETED", "Plano ${plan.id} concluído: ${completed.size}/${plan.steps.size} etapas confirmadas.")
        return ExecutionReport.Success(results)
    }

    private fun fail(plan: NexusPlan, step: PlanStep, detail: String) {
        journal.updatePlanStep(step.id, "FAILED", detail)
        journal.updatePlanStatus(plan.id, "FAILED")
        log("STEP_FAILED", "Etapa ${step.order}: $detail")
    }

    private fun block(plan: NexusPlan, step: PlanStep, detail: String) {
        journal.updatePlanStep(step.id, "BLOCKED", detail)
        journal.updatePlanStatus(plan.id, "BLOCKED")
        log("STEP_BLOCKED", "Etapa ${step.order}: $detail")
    }
}

sealed class ExecutionReport {
    data class Success(val results: List<ToolResult>) : ExecutionReport()
    data class Failed(val message: String, val results: List<ToolResult>) : ExecutionReport()
    data class Blocked(val message: String, val results: List<ToolResult>) : ExecutionReport()
    data class Unknown(val message: String, val results: List<ToolResult>) : ExecutionReport()
}
