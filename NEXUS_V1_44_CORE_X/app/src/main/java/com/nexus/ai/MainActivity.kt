package com.nexus.ai

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import com.nexus.ai.security.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class MainActivity : ComponentActivity() {
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> cameraGranted = granted }
    private var cameraGranted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        setContent { MaterialTheme { NexusApp() } }
    }

    @Composable
    fun NexusApp() {
        val scope = rememberCoroutineScope()
        val settings = remember { NexusSettings(this@MainActivity) }
        val permissionStore = remember { PermissionStore(this@MainActivity) }
        val permissionCenter = remember { PermissionCenter(permissionStore) }

        val memory = remember { MemoryStore(this@MainActivity) }
        val journal = remember { AgentJournal(this@MainActivity) }
        val controller = remember { TaskController(this@MainActivity, journal) }
        var gateway by remember { mutableStateOf(settings.gatewayEndpoint) }
        var appKey by remember { mutableStateOf(settings.appKey) }
        var input by remember { mutableStateOf("") }
        var answer by remember { mutableStateOf("NEXUS V1.40 online.\nAndroid State Observation + Action Adapters + Agent Loop + segurança + verificação + evidência independente + auditoria.") }
        var busy by remember { mutableStateOf(false) }
        var image by remember { mutableStateOf<Bitmap?>(null) }
        var showCamera by remember { mutableStateOf(false) }
        var showJournal by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }
        var showPlugins by remember { mutableStateOf(false) }
        var showPolicies by remember { mutableStateOf(false) }
        var showPolicyEditor by remember { mutableStateOf(false) }
        var showDiagnostics by remember { mutableStateOf(false) }
        var diagnosticsRefresh by remember { mutableIntStateOf(0) }
        var currentCycleId by remember { mutableStateOf<String?>(null) }
        var policyRefresh by remember { mutableIntStateOf(0) }
        val pluginManager = remember { PluginManager(this@MainActivity) }
        val policyStore = remember { PolicyStore(this@MainActivity) }
        val policyRepository = remember { PolicyRepository(policyStore) }
        val riskConfigStore = remember { RiskConfigStore(this@MainActivity) }
        val policyEngine = remember(policyRefresh) { NexusPolicyEngine(CapabilityRegistry(), repository = policyRepository, riskConfig = riskConfigStore.get()) }
        val verifier = remember(policyRefresh) { NexusVerifier(CapabilityRegistry(), policyEngine) }
        val policyAdmin = remember(policyRefresh) { PolicyAdminService(policyStore) { action, ruleId, success -> journal.policyAudit(action, ruleId, success, if (success) "Alteração administrativa aplicada." else "Alteração administrativa rejeitada.") } }
        val riskAdmin = remember(policyRefresh) { RiskConfigAdminService(riskConfigStore) { action, success -> journal.add("admin", "POLICY_ADMIN", "$action success=$success") } }
        var pluginRefresh by remember { mutableIntStateOf(0) }
        var refreshTasks by remember { mutableIntStateOf(0) }
        var webSearch by remember { mutableStateOf(false) }

        val registry = remember(pluginRefresh) { ToolRegistryFactory.create(this@MainActivity, includePlugins = true) }
        val provider = remember(gateway, appKey) { settings.gatewayEndpoint = gateway; settings.appKey = appKey; settings.provider() }
        val goalManager = remember(journal) { GoalManager(journal) }
        val androidHub = remember { AndroidToolHub(this@MainActivity) }
        val androidEvidenceSources = remember { androidHub.evidenceSources() }
        val agent = remember(provider, journal, webSearch, permissionCenter, goalManager, verifier) {
            NexusAgent(
                provider, registry, journal, verifier = verifier, permissionCenter = permissionCenter,
                webSearch = webSearch, goalManager = goalManager,
                androidStateObservationAdapter = androidHub.stateObservationAdapter(),
                androidActionSelector = AndroidActionSelector(androidHub.actionRegistry(), journal),
                androidEvidenceSources = androidEvidenceSources
            )
        }
        val core = remember(agent, memory, goalManager) { NexusCore(agent, memory, registry, journal) }
        val streaming = provider as? StreamingProvider

        fun capturePayload(): ImagePayload? = image?.let { bitmap ->
            ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                ImagePayload("image/jpeg", Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP))
            }
        }

        fun send() {
            if (input.isBlank() || busy) return
            if (webSearch && gateway.isBlank()) {
                webSearch = false
                answer = "🌐 A Pesquisa na Web exige um gateway remoto configurado."
                return
            }
            val text = input.trim(); input = ""; busy = true; answer = ""
            val cycle = NexusCycleTrace.begin(text)
            currentCycleId = cycle.cycleId
            journal.add("system", "CYCLE_BEGIN", "${cycle.cycleId}: objetivo recebido", cycle.cycleId)
            scope.launch {
                try {
                    cycle.stage(CycleStageName.OBSERVE, if (image != null) "Imagem anexada para análise." else "Contexto local preparado.")
                    val payload = capturePayload()
                    cycle.stage(CycleStageName.DECIDE, "Rota de processamento selecionada.")
                    cycle.stage(CycleStageName.POLICY, "Fluxo submetido às políticas existentes.")
                    if (payload == null && streaming != null && !webSearch) {
                        cycle.stage(CycleStageName.ACT, "Streaming iniciado.")
                        if (gateway.isNotBlank()) {
                            val gate = permissionCenter.await(null, PermissionKind.NETWORK, "Acesso à rede", "O NEXUS precisa usar o gateway remoto para transmitir esta conversa.")
                            if (gate !is PermissionGate.Result.Allowed) {
                                cycle.stage(CycleStageName.VERIFY, "Acesso à rede não autorizado.")
                                val report = cycle.finish(CycleResult.BLOCKED, "Rede não autorizada.")
                                journal.add("system", "CYCLE_BLOCKED", report.compact(), cycle.cycleId)
                                answer = "🚫 Rede não autorizada."
                                return@launch
                            }
                        }
                        val messages = core.prepareMessages(text)
                        streaming.streamText(messages, cycle.cycleId).collect { delta -> answer += delta }
                        core.rememberConversation(text, answer)
                    } else {
                        cycle.stage(CycleStageName.ACT, "Core executando objetivo.")
                        answer = core.send(text, payload).text
                    }
                    cycle.stage(CycleStageName.EVIDENCE, "Resposta/estado pós-execução disponível para verificação.")
                    cycle.stage(CycleStageName.VERIFY, "Ciclo concluído sem exceção.")
                    val report = cycle.finish(CycleResult.SUCCESS, "Processamento concluído.")
                    journal.add("system", "CYCLE_COMPLETE", report.compact(), cycle.cycleId)
                    image = null
                } catch (e: Exception) {
                    cycle.stage(CycleStageName.VERIFY, "Exceção durante o ciclo.")
                    val report = cycle.finish(CycleResult.FAILED, e.message ?: "erro desconhecido")
                    journal.add("system", "CYCLE_FAILED", report.compact(), cycle.cycleId)
                    answer = "Erro: ${'$'}{e.message}"
                } finally { currentCycleId = null; busy = false }
            }
        }

        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("NEXUS AI", style = MaterialTheme.typography.headlineMedium)
            Text("V1.40 • Streaming Cycle Correlation", style = MaterialTheme.typography.labelLarge)
            Text("Estado: ${agent.state}", style = MaterialTheme.typography.labelMedium)
            Text("Runtime: ${BuildConfig.VERSION_NAME} • Diagnóstico: pronto", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(10.dp))

            Surface(Modifier.weight(1f).fillMaxWidth(), tonalElevation = 2.dp) {
                Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                    Text(answer.ifBlank { "▋" })
                    if (image != null) { Spacer(Modifier.height(10.dp)); Text("📸 Imagem capturada pronta para análise.") }
                }
            }

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Fale com o NEXUS") }, enabled = !busy)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = webSearch, onCheckedChange = { webSearch = it }, enabled = !busy)
                Column {
                    Text("🌐 Web/Search", style = MaterialTheme.typography.labelLarge)
                    Text(if (webSearch) "Pesquisa atual habilitada" else "Somente conhecimento do modelo", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { if (cameraGranted) showCamera = true else cameraPermission.launch(Manifest.permission.CAMERA) }) { Text("📸") }
                Button(onClick = { showJournal = true; refreshTasks++ }) { Text("🧾") }
                Button(onClick = { showSettings = true }) { Text("⚙") }
                Button(onClick = { showPlugins = true; pluginRefresh++ }) { Text("🧩") }
                Button(onClick = { showPolicies = true; policyRefresh++ }) { Text("🛡") }
                Button(onClick = { showPolicyEditor = true; policyRefresh++ }) { Text("🧪") }
                Button(onClick = { showDiagnostics = true; diagnosticsRefresh++ }) { Text("🩺") }
                Button(onClick = {
                    permissionCenter.request(null, PermissionKind.NETWORK,
                        "Acesso à rede", "Permitir que o NEXUS use o gateway remoto.")
                }) { Text("🔐") }
                Button(onClick = {
                    if (input.isNotBlank()) {
                        val task = input.trim(); input = ""
                        val workId = controller.enqueue(task)
                        answer = "⏳ Tarefa em segundo plano.\nID: $workId\n\nO NEXUS poderá retomá-la pelo WorkManager."
                        refreshTasks++
                    }
                }, enabled = input.isNotBlank() && !busy) { Text("⏳") }
                Button(onClick = { send() }, enabled = input.isNotBlank() && !busy) { Text(if (busy) "…" else "Enviar") }
            }
        }

        permissionCenter.pending.value?.let { request ->
            AlertDialog(
                onDismissRequest = { permissionCenter.decide(PermissionDecision.DENY) },
                title = { Text("🔐 Autorização necessária") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(request.title, style = MaterialTheme.typography.titleMedium)
                        Text(request.reason)
                        Text("Capacidade: ${request.kind.name}")
                        Text("O NEXUS não executará esta ação enquanto você não autorizar.")
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        permissionCenter.decide(PermissionDecision.ALLOW)
                    }) { Text("Permitir") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        permissionCenter.decide(PermissionDecision.DENY)
                    }) { Text("Negar") }
                }
            )
        }

        if (showPlugins) {
            AlertDialog(
                onDismissRequest = { showPlugins = false },
                confirmButton = { Button(onClick = { showPlugins = false }) { Text("Fechar") } },
                title = { Text("🧩 Plugins do NEXUS") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("Ferramentas locais e seus manifestos", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        pluginManager.manifests().forEach { plugin ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text("${plugin.name} v${plugin.version}")
                                    Text("${plugin.id} • ${plugin.origin} • risco ${plugin.risk}", style = MaterialTheme.typography.labelSmall)
                                    Text(plugin.description, style = MaterialTheme.typography.bodySmall)
                                    Text("Permissão: ${plugin.permission.name}", style = MaterialTheme.typography.labelSmall)
                                }
                                Switch(checked = plugin.enabled, onCheckedChange = {
                                    pluginManager.setEnabled(plugin.id, it); pluginRefresh++
                                })
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        Text("Plugins importados começam desativados. A execução continua sujeita ao PermissionGate.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            )
        }

        if (showPolicies) {
            AlertDialog(
                onDismissRequest = { showPolicies = false },
                confirmButton = { Button(onClick = { showPolicies = false }) { Text("Fechar") } },
                title = { Text("🛡 Console de políticas") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("Regras persistentes • prioridade maior vence", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        policyRepository.records().forEach { rule ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text("${rule.id} • ${rule.action}")
                                    Text("${rule.origin} • p=${rule.priority} • ${rule.matcherType}${rule.matcherValue?.let { "=$it" } ?: ""}", style = MaterialTheme.typography.labelSmall)
                                    Text(rule.description, style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = rule.enabled, enabled = rule.origin != PolicyOrigin.BUILTIN, onCheckedChange = {
                                    policyAdmin.setEnabled(rule.id, it)
                                    policyRefresh++
                                })
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        Text("Regras BUILTIN são protegidas. O agente não recebe acesso ao console administrativo e não pode alterar suas próprias políticas.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            )
        }

        if (showPolicyEditor) {
            var id by remember { mutableStateOf("") }
            var description by remember { mutableStateOf("") }
            var priority by remember { mutableStateOf("20") }
            var matcherType by remember { mutableStateOf(PolicyMatchType.TOOL) }
            var matcherValue by remember { mutableStateOf("") }
            var action by remember { mutableStateOf(PolicyAction.REQUIRE_CONFIRMATION) }
            var reason by remember { mutableStateOf("") }
            var simTool by remember { mutableStateOf("device_info") }
            var simCapability by remember { mutableStateOf(CapabilityKind.DEVICE_INFO) }
            var simPermission by remember { mutableStateOf(PermissionKind.TOOL_EXECUTION) }
            var simReadOnly by remember { mutableStateOf(true) }
            var simBackground by remember { mutableStateOf(false) }
            var simulationText by remember { mutableStateOf("") }
            var mediumThreshold by remember { mutableStateOf(riskConfigStore.get().mediumThreshold.toString()) }
            var highThreshold by remember { mutableStateOf(riskConfigStore.get().highThreshold.toString()) }
            var criticalThreshold by remember { mutableStateOf(riskConfigStore.get().criticalThreshold.toString()) }
            AlertDialog(
                onDismissRequest = { showPolicyEditor = false },
                confirmButton = {
                    Button(onClick = {
                        val p = priority.toIntOrNull() ?: 0
                        val result = policyAdmin.createOrUpdate(PolicyRuleRecord(id.trim(), description.trim(), p, matcherType, matcherValue.trim().ifBlank { null }, action, reason.trim(), PolicyOrigin.USER))
                        simulationText = result.fold({ "✅ Regra salva: ${it.id}" }, { "🚫 ${it.message}" })
                        policyRefresh++
                    }, enabled = id.isNotBlank() && description.isNotBlank()) { Text("Salvar") }
                },
                dismissButton = { TextButton(onClick = { showPolicyEditor = false }) { Text("Fechar") } },
                title = { Text("🧪 Editor + Simulação de Política") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Criar/editar regra USER", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(id, { id = it }, label = { Text("ID") })
                        OutlinedTextField(description, { description = it }, label = { Text("Descrição") })
                        OutlinedTextField(priority, { priority = it }, label = { Text("Prioridade 1–50") })
                        Text("Matcher: ${matcherType.name}")
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            PolicyMatchType.values().filter { it != PolicyMatchType.ANY }.forEach { type ->
                                TextButton(onClick = { matcherType = type }) { Text(type.name.take(10)) }
                            }
                        }
                        OutlinedTextField(matcherValue, { matcherValue = it }, label = { Text("Valor do matcher (quando necessário)") })
                        Text("Ação: ${action.name}")
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(PolicyAction.ALLOW, PolicyAction.DENY, PolicyAction.REQUIRE_CONFIRMATION, PolicyAction.REQUIRE_VERIFICATION).forEach { a ->
                                TextButton(onClick = { action = a }) { Text(a.name.take(9)) }
                            }
                        }
                        OutlinedTextField(reason, { reason = it }, label = { Text("Motivo") })
                        HorizontalDivider()
                        Text("⚖️ Limiares administrativos de risco", style = MaterialTheme.typography.titleMedium)
                        Text("Score 0–100. Alterações aqui só aumentam/reduzem a exigência de restrição; não concedem permissões Android.", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(mediumThreshold, { mediumThreshold = it }, label = { Text("Médio") }, modifier = Modifier.weight(1f))
                            OutlinedTextField(highThreshold, { highThreshold = it }, label = { Text("Alto") }, modifier = Modifier.weight(1f))
                            OutlinedTextField(criticalThreshold, { criticalThreshold = it }, label = { Text("Crítico") }, modifier = Modifier.weight(1f))
                        }
                        Button(onClick = {
                            val result = riskAdmin.update(mediumThreshold.toIntOrNull() ?: -1, highThreshold.toIntOrNull() ?: -1, criticalThreshold.toIntOrNull() ?: -1)
                            simulationText = result.fold({ "✅ Limiares salvos: médio=${it.mediumThreshold}, alto=${it.highThreshold}, crítico=${it.criticalThreshold}" }, { "🚫 ${it.message}" })
                            policyRefresh++
                        }) { Text("Salvar limiares") }
                        HorizontalDivider()
                        Text("Simulação — não executa ferramenta", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(simTool, { simTool = it }, label = { Text("Tool") })
                        Text("Capability: ${simCapability.name}")
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            CapabilityKind.values().forEach { c -> TextButton(onClick = { simCapability = c }) { Text(c.name.take(8)) } }
                        }
                        Text("Permission: ${simPermission.name}")
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            PermissionKind.values().take(5).forEach { q -> TextButton(onClick = { simPermission = q }) { Text(q.name.take(7)) } }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Checkbox(simReadOnly, { simReadOnly = it }); Text("somente leitura")
                            Checkbox(simBackground, { simBackground = it }); Text("background")
                        }
                        Button(onClick = {
                            val result = PolicySimulation(policyEngine).simulate(PolicyContext(simTool, simCapability, simReadOnly, simBackground, simPermission, requestOrigin = RequestOrigin.USER, taskType = "policy_simulation"))
                            simulationText = "Decisão: ${result.decision.action}\nRegra vencedora: ${result.matchedRuleId ?: result.decision.ruleId}\nRisco base: ${result.decision.risk}\nRiskScore: ${result.riskScore.value}/100 (${result.riskScore.band})\nFatores: ${result.riskScore.explain()}\nMotivo: ${result.decision.reason}\nRegras avaliadas: ${result.evaluatedRules.size}\nIgnoradas: ${result.ignoredRules.size}"
                        }) { Text("Simular decisão") }
                        if (simulationText.isNotBlank()) Text(simulationText, style = MaterialTheme.typography.bodySmall)
                    }
                }
            )
        }

        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                title = { Text("⚙ Gateway do NEXUS") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = gateway, onValueChange = { gateway = it }, label = { Text("Endpoint /v1/agent") })
                        OutlinedTextField(value = appKey, onValueChange = { appKey = it }, label = { Text("X-Nexus-App-Key (opcional)") })
                        Text(if (gateway.isBlank()) "Modo atual: demonstração offline" else "Modo atual: gateway remoto + streaming")
                    }
                },
                confirmButton = { Button(onClick = { settings.gatewayEndpoint = gateway; settings.appKey = appKey; showSettings = false }) { Text("Salvar") } },
                dismissButton = { TextButton(onClick = { gateway = ""; appKey = ""; settings.gatewayEndpoint = ""; settings.appKey = "" }) { Text("Offline") } }
            )
        }

        if (showJournal) {
            AlertDialog(
                onDismissRequest = { showJournal = false },
                confirmButton = { Button(onClick = { showJournal = false }) { Text("Fechar") } },
                title = { Text("🧾 Tarefas, ferramentas e auditoria") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("Tarefas", style = MaterialTheme.typography.titleMedium)
                        if (refreshTasks >= 0) journal.recentTasks(10).forEach { task ->
                            Text("• ${task.state}: ${task.objective}")
                            task.workId?.let { workId ->
                                TextButton(onClick = { controller.cancel(workId); refreshTasks++ }) { Text("Cancelar") }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Eventos", style = MaterialTheme.typography.titleMedium)
                        journal.recent(20).forEach { Text("• ${it.type}: ${it.detail}", modifier = Modifier.padding(bottom = 5.dp)) }
                    }
                }
            )
        }

        if (showDiagnostics) {
            val diagnosticCycleId = currentCycleId ?: journal.recent(50).firstOrNull { it.type.startsWith("CYCLE_") && it.cycleId != null }?.cycleId ?: journal.recentTasks(1).firstOrNull()?.cycleId
            val snapshot = remember(diagnosticsRefresh, diagnosticCycleId) { journal.diagnosticSnapshot(10, diagnosticCycleId) }
            val stale = snapshot.staleEvidence()
            AlertDialog(
                onDismissRequest = { showDiagnostics = false },
                confirmButton = { Button(onClick = { showDiagnostics = false }) { Text("Fechar") } },
                title = { Text("🩺 Diagnóstico do NEXUS") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("Build/runtime", style = MaterialTheme.typography.titleMedium)
                        Text("NEXUS ${BuildConfig.VERSION_NAME} • versionCode ${BuildConfig.VERSION_CODE}")
                        Text("Android SDK ${Build.VERSION.SDK_INT} • ${Build.MANUFACTURER} ${Build.MODEL}")
                        val cameraOk = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                        val audioOk = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        Text("Câmera: ${if (cameraOk) "CONCEDIDA" else "NÃO CONCEDIDA"} • Áudio: ${if (audioOk) "CONCEDIDO" else "NÃO CONCEDIDO"}")
                        Text("Rede: ${if (gateway.isBlank()) "OFFLINE" else "GATEWAY CONFIGURADO"}")
                        Text("Ciclo: ${diagnosticCycleId ?: "não identificado"} • ${snapshot.cycleSummary(diagnosticCycleId)}")
                        HorizontalDivider()
                        Text("Resultados de ação", style = MaterialTheme.typography.titleMedium)
                        if (snapshot.actionResults.isEmpty()) Text("Nenhum resultado registrado.")
                        snapshot.actionResults.forEach { Text("• ${it.action} → ${it.state}: ${it.detail}") }
                        HorizontalDivider()
                        Text("Evidências independentes", style = MaterialTheme.typography.titleMedium)
                        if (snapshot.evidence.isEmpty()) Text("Nenhuma evidência registrada.")
                        snapshot.evidence.forEach { e ->
                            val age = e.ageLabel()
                            Text("• ${e.action} / ${e.source} / confiança ${(e.confidence * 100).toInt()}% / $age: ${e.fact}")
                        }
                        if (stale.isNotEmpty()) Text("⚠️ ${stale.size} evidência(s) com mais de 30s; não devem ser tratadas como confirmação atual.")
                        HorizontalDivider()
                        Text("Contratos", style = MaterialTheme.typography.titleMedium)
                        if (snapshot.contracts.isEmpty()) Text("Nenhum contrato registrado.")
                        snapshot.contracts.forEach { Text("• ${it.action} / ${it.stage} → ${it.status}: ${it.detail}") }
                        HorizontalDivider()
                        Text("Eventos recentes", style = MaterialTheme.typography.titleMedium)
                        snapshot.events.take(10).forEach { Text("• ${it.type}: ${it.detail}") }
                    }
                }
            )
        }

        if (showCamera) {
            AlertDialog(onDismissRequest = { showCamera = false }, confirmButton = {}, title = { Text("Visão do NEXUS") }, text = {
                val preview = remember { PreviewView(this@MainActivity) }
                val visionCapture = remember { NexusVisionCapture(this@MainActivity, this@MainActivity, preview, ContextCompat.getMainExecutor(this@MainActivity)) }
                var cameraReady by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    visionCapture.start(
                        onReady = { cameraReady = true; journal.cameraDiagnosticEvent("READY", "CameraX inicializada e pronta para captura.") },
                        onError = { answer = "📷 $it"; cameraReady = false; journal.cameraDiagnosticEvent("ERROR", it) }
                    )
                }
                DisposableEffect(Unit) {
                    onDispose { visionCapture.stop(); journal.cameraDiagnosticEvent("STOP", "Controller CameraX encerrado pelo lifecycle da tela.") }
                }
                Column {
                    AndroidView(factory = { preview }, modifier = Modifier.fillMaxWidth().height(300.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(if (cameraReady) "Câmera pronta para captura." else "Inicializando câmera…", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        enabled = cameraReady,
                        onClick = {
                            visionCapture.takePhoto(
                                { bmp -> image = bmp; answer = "📸 Imagem capturada e pronta para análise."; journal.cameraDiagnosticEvent("CAPTURE_SUCCESS", "Imagem capturada com sucesso."); showCamera = false },
                                { answer = "📷 $it"; journal.cameraDiagnosticEvent("CAPTURE_ERROR", it); showCamera = false }
                            )
                        }
                    ) { Text("📸 Capturar") }
                }
            })
        }
    }
}
