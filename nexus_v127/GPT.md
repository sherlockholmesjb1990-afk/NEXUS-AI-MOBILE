# NEXUS AI MOBILE — CONTEXTO PARA A PRÓXIMA IA

## Regra de continuidade
Toda atualização do NEXUS deve entregar um ZIP completo e atualizado. O `GPT.md` deve registrar o histórico, a versão atual, alterações, limitações/testes e, obrigatoriamente, a próxima etapa. Nunca deixar a próxima IA sem direção.

## Visão do projeto
NEXUS é uma infraestrutura pessoal de agente Android. A IA é um componente; o núcleo é a orquestração segura entre intenção, contexto, planejamento, capabilities, políticas, permissões, ferramentas, verificação, recuperação e auditoria.

## Histórico consolidado
- V1.8: Tool Hub, memória/persistência, PermissionGate, plugins declarativos, auditoria e agente base.
- V1.9: verificação pós-execução, estados CONFIRMED/FAILED/UNKNOWN, auditoria de execução, ToolRegistryFactory e retomada mais segura.
- V1.10: Android Tool Hub e Capability System; `device_info` e `open_url` controlado.
- V1.11: Capability Security + Sandbox lógico/político, risco LOW/MEDIUM/HIGH/CRITICAL e grants preparados.
- V1.12: Planner/Orchestrator, planos persistentes, dependências, preconditions, expected result e checkpoints.
- V1.13: execução multi-etapas, checkpoints, retry conservador e proteção de estado RUNNING/UNKNOWN.
- V1.14: Context Engine com memória relevante, objetivo, tarefa, plano, tools/capabilities e modo foreground/background.
- V1.15: Goal Manager, objetivos persistentes, tarefas, progresso e integração com Context Engine.
- V1.16: Recovery Engine com classificação de falhas, retry limitado, fallback controlado, stop seguro e auditoria de recovery.
- V1.17: Policy Engine centralizado para decidir ALLOW/DENY/REQUIRE_CONFIRMATION/REQUIRE_VERIFICATION sem conceder permissões ou executar ferramentas.
- V1.18: persistência de políticas em SQLite, regras serializáveis, PolicyRepository, PolicyAdminService, console administrativo no app e auditoria de alterações.

## V1.17 — o que foi implementado
- `NexusPolicyEngine` como ponto central de decisão de política.
- `PolicyContext`, `PolicyDecision`, `PolicyRule` e `PolicyAction`.
- Política default-deny quando não existe regra aplicável.
- Capability desabilitada → DENY.
- Capability não permitida em background → DENY.
- DELETE_FILES → confirmação explícita + verificação.
- Ferramenta mutável em background → DENY pela política padrão.
- Risco MEDIUM/HIGH/CRITICAL → confirmação explícita.
- Ferramentas mutáveis → REQUIRE_VERIFICATION.
- Leitura LOW-risk → ALLOW.
- `NexusVerifier` agora consulta o Policy Engine e expõe `requiresVerification` e `ruleId`.
- `MultiStepExecution` passa o modo foreground/background ao Verifier inclusive para fallback.
- O Policy Engine não concede Android permissions, não executa tools e não substitui PermissionGate.
- versionCode 25 / versionName 1.17.

## Arquitetura atual
```text
UI / TaskController
  ↓
ContextEngine
  ↓
NexusAgent
  ├─ GoalManager
  ├─ NexusPlanner
  ├─ MultiStepExecution
  ├─ NexusRecoveryEngine
  ├─ ToolRegistry
  ├─ NexusVerifier
  │   └─ NexusPolicyEngine
  │       └─ CapabilityRegistry / CapabilityCatalog
  │           └─ CapabilitySandbox
  ├─ PermissionCenter → PermissionGate → PermissionStore
  └─ AgentJournal → checkpoints / audit / recovery
       ↓
PluginManager
       ↓
AIProvider
```

## Pipeline de segurança
```text
INTENÇÃO
 ↓
CONTEXT
 ↓
GOAL
 ↓
PLAN
 ↓
POLICY
 ↓
CAPABILITY / SANDBOX
 ↓
VERIFIER
 ↓
PERMISSION GATE
 ↓
EXECUTE
 ↓
VERIFY
 ↓
RECOVERY (se necessário)
 ↓
CHECKPOINT
 ↓
AUDIT
```

## Princípios que não podem ser quebrados
1. Planner/Goal/Context não executam tools.
2. Policy Engine decide requisitos; não concede permissões.
3. PermissionGate continua sendo a autoridade de autorização da ação.
4. Sandbox é lógico/político; não alegar isolamento de processo.
5. Capability Android não deve ser confundida com permissão Android.
6. Estado UNKNOWN/RUNNING potencialmente side-effectful nunca deve ser repetido automaticamente.
7. Ações mutáveis precisam de verificação pós-execução.
8. Plugins continuam declarativos; não permitir execução arbitrária de código.
9. Foreground e background devem obedecer às mesmas políticas de segurança, com restrições adicionais no background.
10. Toda nova capacidade deve ter risco, capability, política, permissão e estratégia de verificação claramente definidos.

## Ferramentas atuais
- calculator — local, LOW, leitura/execução determinística.
- clock — local, LOW.
- device_info — Android, somente leitura.
- open_url — HTTPS, MEDIUM, confirmação e bloqueio em background.
- web_search — provider externo quando habilitado.

## Testes / limitações
- ZIP deve passar por validação estrutural a cada entrega.
- Ambiente de trabalho não possui Gradle Wrapper/Gradle utilizável para build Android local; compilação final deve ser validada no Android Studio/Codemagic.
- V1.17 não cria sandbox de processo; o isolamento continua lógico/político.
- A política é centralizada, mas as regras ainda são estáticas em código. Persistência/edição segura de políticas fica para etapa futura.

# V1.18 — o que foi implementado
- `PolicyStore` persistente em SQLite (`nexus_policy.db`).
- `PolicyRuleRecord` serializável com `PolicyMatchType`; lambdas não são persistidas.
- `PolicyOrigin`: BUILTIN / USER / SYSTEM.
- `PolicyRepository` carrega apenas regras habilitadas e aplica prioridade determinística.
- regras BUILTIN são protegidas contra edição, desativação e exclusão.
- `PolicyAdminService` fornece criação/edição, enable/disable e exclusão somente de regras USER.
- regras USER ficam limitadas a prioridade 1..50 e não podem transformar proteções críticas em ALLOW.
- proteção crítica impede ALLOW para `DELETE_FILES` e para risco HIGH/CRITICAL.
- `NexusPolicyEngine` pode consumir o repositório persistente, mantendo default-deny.
- console administrativo no `MainActivity` permite visualizar regras e ativar/desativar apenas regras USER.
- `AgentJournal` passou para schema 9 e ganhou `policy_audits` para registrar alterações administrativas.
- o agente não recebe `PolicyAdminService`; ferramentas e o próprio agente não conseguem modificar políticas durante a execução.
- ContextEngine/Planner continuam somente consumidores de contexto; não modificam políticas.
- versionCode 26 / versionName 1.18.

## Arquitetura atual
```text
UI / Policy Console / TaskController
  ↓
ContextEngine
  ↓
NexusAgent
  ├─ GoalManager
  ├─ NexusPlanner
  ├─ MultiStepExecution
  ├─ NexusRecoveryEngine
  ├─ ToolRegistry
  ├─ NexusVerifier
  │   └─ NexusPolicyEngine
  │       └─ PolicyRepository → PolicyStore (SQLite)
  │       └─ CapabilityRegistry / CapabilityCatalog
  │           └─ CapabilitySandbox
  ├─ PermissionCenter → PermissionGate → PermissionStore
  └─ AgentJournal → checkpoints / audit / recovery / policy-audit
       ↓
PluginManager
       ↓
AIProvider
```

## Pipeline de segurança
```text
INTENÇÃO
 ↓
CONTEXT
 ↓
GOAL
 ↓
PLAN
 ↓
POLICY (persistente)
 ↓
CAPABILITY / SANDBOX
 ↓
VERIFIER
 ↓
PERMISSION GATE
 ↓
EXECUTE
 ↓
VERIFY
 ↓
RECOVERY (se necessário)
 ↓
CHECKPOINT
 ↓
AUDIT
```

## Testes / limitações
- ZIP validado estruturalmente após atualização.
- Build Android local ainda depende de Android Studio/Codemagic porque o projeto não possui Gradle Wrapper utilizável neste ambiente.
- `nexus_policy.db` é separado do `AgentJournal` para isolamento de armazenamento de regras; mudanças administrativas são auditadas no `AgentJournal`.
- O console V1.18 permite visualizar e habilitar/desabilitar regras USER; criação/edição avançada está disponível via `PolicyAdminService` para futura UI de edição.
- Sandbox continua lógico/político, não isolamento de processo.
- BUILTIN continua protegido e não pode ser desativado pelo console.

# ROADMAP — PRÓXIMA ETAPA OBRIGATÓRIA

## V1.19 — Policy Editor + Policy Simulation
A próxima IA deve começar aqui.

Objetivo: transformar o console em uma ferramenta administrativa completa e permitir simular uma decisão de política antes de aplicá-la.

Implementar:
- tela de edição de regras USER com formulário seguro;
- criação de regras por `TOOL`, `PERMISSION`, `RISK_AT_LEAST`, `MUTATING` e `READ_ONLY_LOW`;
- validação visual de prioridade, origem e ação;
- prévia da decisão sem executar a ferramenta (`PolicySimulation`);
- mostrar qual regra venceu e quais regras foram ignoradas;
- bloquear qualquer tentativa de enfraquecer proteções BUILTIN/críticas;
- histórico de versões das regras USER, com rollback administrativo seguro;
- auditoria de criação, edição, ativação, desativação, exclusão e rollback;
- manter o agente sem acesso ao serviço administrativo;
- atualizar `GPT.md`, `README.md` e entregar ZIP completo.

### Critério de sucesso da V1.19
O administrador deve conseguir criar/editar uma regra USER, simular uma decisão para uma ferramenta/contexto sem executar nada, visualizar a regra vencedora e ter todas as alterações auditadas, sem conseguir remover ou enfraquecer as proteções BUILTIN críticas.

# V1.19 — Policy Editor + Policy Simulation
- Editor administrativo para regras USER no `MainActivity`.
- Suporte a matchers declarativos: TOOL, PERMISSION, RISK_AT_LEAST, MUTATING, BACKGROUND_MUTATING e READ_ONLY_LOW.
- Simulação explicável via `PolicySimulation`: decisão, regra vencedora, regras avaliadas e regras ignoradas.
- Simulação é somente análise: não executa ferramentas, não altera permissões e não modifica políticas.
- Histórico persistente de versões em `policy_versions` (schema V2 do `nexus_policy.db`).
- Snapshot JSON de regras e operações UPSERT/ENABLE/DISABLE/DELETE.
- Rollback administrativo seguro somente para regras USER, reaplicando todas as validações de segurança.
- BUILTIN continua protegido contra edição, exclusão, desativação ou enfraquecimento.
- Auditoria de operações administrativas preservada.
- versionCode 27 / versionName 1.19.

## Critério de sucesso V1.19
Administrador consegue criar/editar uma regra USER, simular uma decisão sem executar ferramenta, identificar a regra vencedora e recuperar uma versão anterior; nenhuma proteção BUILTIN crítica pode ser enfraquecida e toda mudança administrativa é auditada.

# ROADMAP — PRÓXIMA ETAPA OBRIGATÓRIA

## V1.20 — Context-aware Policy + Risk Scoring
A próxima IA deve começar aqui.

Objetivo: fazer o Policy Engine considerar o contexto operacional de forma mais rica, sem permitir que contexto reduza proteções críticas.

Implementar:
- `RiskScore` determinístico combinando risco da capability, mutabilidade, background, sensibilidade da permissão e origem da ferramenta;
- `PolicyContext` enriquecido com origem da solicitação, tipo de tarefa/goal, confiança da verificação e estado do usuário (quando explicitamente disponível);
- regras que possam exigir confirmação quando o score atingir faixas configuráveis;
- explicação detalhada de como o score foi calculado;
- simulação V1.19 deve mostrar também o score e seus fatores;
- persistir somente configurações administrativas necessárias, nunca dados sensíveis desnecessários;
- manter default-deny e impossibilidade do agente alterar políticas;
- atualizar `GPT.md`, `README.md` e entregar ZIP completo.

### Restrições para V1.20
- RiskScore é camada de decisão, não autorização Android.
- Nunca usar score para transformar uma proteção BUILTIN crítica em ALLOW.
- Não executar ferramenta durante cálculo/simulação.
- Não introduzir execução arbitrária de plugins.

# V1.20 — Context-aware Policy + Risk Scoring
- Added deterministic `RiskScorer` with explainable factors.
- Enriched `PolicyContext` with request origin, task type, verification confidence and explicit user state.
- `NexusPolicyEngine` now calculates a bounded 0..100 RiskScore and RiskBand before policy decision.
- Risk scoring can only increase restrictions: HIGH can turn ALLOW into REQUIRE_CONFIRMATION; CRITICAL can turn ALLOW/REQUIRE_VERIFICATION into REQUIRE_CONFIRMATION; it never weakens DENY or BUILTIN protections.
- `PolicySimulationResult` exposes the RiskScore and its factors.
- `RiskConfigStore` persists only administrative thresholds; it does not store task/user content.
- `NexusVerifier.Decision` exposes score and band for future UI/audit use.
- Existing PermissionGate, Capability, Sandbox, Policy, Planner, Goal, MultiStep, Recovery, Verification and Audit layers remain authoritative.
- versionCode 28 / versionName 1.20.

## Tests / limitations V1.20
- Static source validation performed in this environment.
- Android build still requires a configured Android/Gradle environment (no usable Gradle Wrapper in this workspace).
- RiskScore is deterministic and bounded to 0..100.
- RiskScore is advisory/restrictive and is not an Android permission grant.
- No tool execution occurs during risk scoring or simulation.
- User state is only considered when explicitly supplied; default is UNKNOWN.

# ROADMAP — PRÓXIMA ETAPA OBRIGATÓRIA

## V1.21 — Goal Intelligence + State/Observation Foundation
The next AI must continue here.

Objective: make persistent goals more intelligent and give the agent a read-only observation layer before it acts.

Implement:
- enrich `GoalManager` with goal classification, priority, deadline/expiry, success criteria and measurable progress;
- create a read-only `NexusObservation` model for current task/goal state, recent tool outcomes, checkpoints and relevant context;
- create `ObservationStore` with bounded retention and no unnecessary sensitive data;
- let the Context Engine include observations in planning context;
- add goal-to-observation reconciliation: determine what is completed, pending, blocked or unknown without executing tools;
- expose observation data to Planner as facts, never as permissions;
- keep Policy/Capability/Sandbox/PermissionGate authoritative;
- update `GPT.md`, `README.md` and deliver the complete ZIP.

### V1.21 safety constraints
- Observation is read-only.
- The agent cannot write observations as if they were verified facts.
- Unknown state must remain UNKNOWN; never infer success from absence of errors.
- No autonomous permission escalation.
- No arbitrary plugin code execution.

## V1.21 — Goal Intelligence + State/Observation Foundation
Implemented from the V1.20 roadmap.

### Goal Intelligence
- `NexusGoal` now supports `priority`, optional `deadlineAt` and up to 10 non-blank `successCriteria` entries.
- `GoalManager.assess()` exposes a deterministic `GoalAssessment` with goal kind, priority, deadline, progress, next task and state.
- `GoalIntelligence.classify()` provides conservative classification: ACTION, RESEARCH, ORGANIZATION, MAINTENANCE or UNKNOWN.
- Goal reconciliation derives state only from persisted goal/task statuses. It never executes a tool.
- FAILED/CANCELLED or otherwise unresolved state is never silently converted into success.

### Observation Foundation
- Added `NexusObservation` with kind, subject, state, fact, confidence, source, creation and optional expiry.
- Added `ObservationStore` with bounded retention per subject.
- Added SQLite `observations` table and schema V10 migration.
- ContextEngine now reconciles the active goal and injects recent observations into the model's planning context.
- Observations are read-only facts for planning; they do not grant permissions or execute actions.
- Observation confidence is explicit and unknown state is preserved as UNKNOWN.

### Database hardening
- Fresh databases now create goals, recovery events, policy audits and observations in `onCreate`, matching the upgrade path.

### Safety constraints preserved
- Agent cannot write arbitrary verified observations.
- No observation can grant a capability or Android permission.
- No autonomous permission escalation.
- No arbitrary plugin code execution.
- Absence of an error is not treated as proof of success unless the tool's verification explicitly confirms it.

### Version
- versionCode 29
- versionName 1.21

## Tests / limitations V1.21
- Static source/structure validation performed.
- Android build was not executed because this workspace still lacks a usable Gradle Wrapper/Android build environment.
- Observation persistence, migration SQL and source references were inspected.
- Runtime validation on a real Android device remains required.

# ROADMAP — PRÓXIMA ETAPA OBRIGATÓRIA

## V1.22 — State/Observation Adapters + Tool Outcome Integration
The next AI must continue here.

Objective: connect real, read-only observations to tool results and selected Android state without allowing observations to become an unauthorized execution channel.

Implement:
- an `ObservationAdapter` interface for safe read-only state providers;
- adapters for task state, plan/checkpoint state and verified tool outcomes;
- normalize observations into stable facts with source, timestamp and confidence;
- make `ExecutionVerification` publish observations only after explicit verification;
- expose a bounded observation snapshot to Planner/Context Engine;
- prevent model/tool output from directly claiming `CONFIRMED` state;
- preserve UNKNOWN when the system cannot observe the postcondition;
- add tests for stale, conflicting and unknown observations;
- update `GPT.md`, `README.md` and deliver the complete ZIP.

### V1.22 safety constraints
- Observation adapters are read-only.
- Observation data cannot grant capabilities, permissions or policy exceptions.
- Tool output is evidence, not automatically a verified fact.
- Conflicting observations resolve conservatively to UNKNOWN unless a trusted verifier establishes precedence.
- No arbitrary plugin code execution.

## V1.22 — State/Observation Adapters + Tool Outcome Integration
Implemented from the V1.21 roadmap.

### Observation adapters
- Added `ObservationAdapter` as a read-only provider contract.
- Added `TaskObservationAdapter` for persisted task state.
- Added `PlanCheckpointObservationAdapter` for persisted plan/checkpoint state.
- Added `VerifiedToolOutcomeAdapter` to publish tool observations only after an explicit `VerificationResult`.
- Added `ObservationCoordinator` and bounded `ObservationSnapshot` for Context/Planner consumption.
- Added `ObservationReconciler` with conservative conflict handling.
- Added `ObservationConsistency` deterministic checks for freshness and conflicting evidence.

### Tool outcome integration
- Multi-step execution now publishes an observation only after `verifyResult()` runs.
- Verification is performed against the actually executed tool/call, including controlled fallbacks.
- Tool output alone is never treated as a confirmed fact.
- UNKNOWN verification remains UNKNOWN with low confidence.

### State integration
- ContextEngine now consumes adapter-derived task/plan observations in addition to persisted observations.
- Observation snapshots are bounded and read-only.
- Stale observations are filtered before snapshotting.
- Conflicting observations resolve conservatively to UNKNOWN unless a trusted verified-tool outcome establishes precedence.

### Safety constraints preserved
- Observation adapters cannot execute tools.
- Observation data cannot grant capabilities, permissions or policy exceptions.
- No autonomous permission escalation.
- No arbitrary plugin code execution.
- Absence of an error is not proof of success.

### Version
- versionCode 30
- versionName 1.22

## Tests / limitations V1.22
- Static source/structure validation performed.
- Freshness, conflict and unknown-state handling have deterministic source-level checks.
- Android build was not executed because this workspace still lacks a usable Gradle Wrapper/Android build environment.
- Runtime validation on a real Android device remains required.

# ROADMAP — PRÓXIMA ETAPA OBRIGATÓRIA

## V1.23 — Agent Loop: Observe → Decide → Act → Verify
The next AI must continue here.

Objective: create the first explicit bounded agent loop that uses observations to choose the next step, while keeping every action behind Policy, Capability, Sandbox, Verifier and PermissionGate.

Implement:
- `NexusAgentLoop` with explicit states OBSERVE, DECIDE, ACT, VERIFY, RECOVER, COMPLETE and BLOCKED;
- a bounded iteration budget and cancellation support;
- consume `ObservationSnapshot` before each decision;
- make the decision layer produce a proposed tool call, never execute directly;
- route proposed calls through the existing MultiStep/Policy/security pipeline;
- after execution, require verification before the loop can advance;
- if observation is UNKNOWN, do not assume success; choose verify, recover or stop;
- persist loop checkpoints and terminal reason in `AgentJournal`;
- expose a read-only loop trace to Context/Planner;
- update `GPT.md`, `README.md` and deliver the complete ZIP.

### V1.23 safety constraints
- The loop must never bypass PermissionGate, Policy, Capability or Sandbox.
- The model cannot directly mark an action as completed.
- UNKNOWN is not SUCCESS.
- Loop budget must be finite.
- No self-modification of security policy or permissions.
- No arbitrary plugin code execution.

## V1.23 — Agent Loop: Observe → Decide → Act → Verify
Implemented from the V1.22 roadmap.

### Agent Loop
- Added `AgentLoop` as an explicit bounded state controller with phases OBSERVE, DECIDE, ACT, VERIFY, RECOVER, COMPLETE, BLOCKED and CANCELLED.
- The loop has a finite iteration budget (1..20) and checks coroutine cancellation before every iteration.
- `NexusAgent` now obtains a bounded `ObservationSnapshot` before each decision cycle and records that evidence in the loop trace.
- The loop does not execute tools itself; proposed actions continue through `MultiStepExecution`, Policy, Capability, Sandbox, Verifier and PermissionGate.
- Verified tool outcomes remain the only source that can publish verified tool observations.
- Recovery/UNKNOWN paths are explicitly represented as RECOVER rather than being treated as success.
- Added read-only `traceSnapshot()` and `terminalReason()` for future Context/Planner consumers.
- Loop trace is bounded to 100 entries in memory and also recorded in `AgentJournal` events.

### Safety constraints preserved
- No loop phase grants permissions or capabilities.
- The model cannot mark an action as completed.
- UNKNOWN is never treated as SUCCESS.
- The loop budget is finite.
- Cancellation is checked between cycles.
- Existing Policy, Capability, Sandbox, Verifier and PermissionGate remain authoritative.
- No self-modification of security policy or permissions.
- No arbitrary plugin code execution.

### Version
- versionCode 31
- versionName 1.23

## Tests / limitations V1.23
- Static source/structure validation performed.
- Verified that `NexusAgent` now consumes a bounded observation snapshot before each loop decision.
- Verified that terminal and recovery phases are explicit and that the loop has a finite budget.
- Android build was not executed because this workspace still lacks a usable Gradle Wrapper/Android build environment.
- Runtime validation on a real Android device remains required.

# ROADMAP — PRÓXIMA ETAPA OBRIGATÓRIA

## V1.24 — Android State Observation + Action Adapters
The next AI must continue here.

Objective: give the NEXUS real, read-only Android state observations and a small set of controlled action adapters, without weakening the security pipeline.

Implement:
- read-only Android observation adapters for app/device state that are safe and permission-aware;
- controlled action adapters for a minimal set of low-risk Android actions;
- every action must declare capability, permission, mutability and verification contract;
- action adapters must integrate with the existing ToolRegistry/AndroidToolHub;
- post-action verification must produce observations only through `VerifiedToolOutcomeAdapter`;
- preserve UNKNOWN when Android cannot prove the postcondition;
- add explicit precondition/postcondition models for Android actions;
- update `GPT.md`, `README.md` and deliver the complete ZIP.

### V1.24 safety constraints
- Read-only observation adapters cannot mutate device state.
- Action adapters cannot bypass Policy, Capability, Sandbox, Verifier or PermissionGate.
- No arbitrary intents or arbitrary shell/command execution.
- No background action may bypass Android platform restrictions.
- Tool output is evidence, not proof; only explicit verification can create a confirmed observation.
- No autonomous permission escalation.

## V1.24 — Android State Observation + Action Adapters
Implemented from the V1.23 roadmap.

### Android state observation
- Added `AndroidStateObservationAdapter` with read-only observations for battery, active network transport, screen interactivity and NEXUS process foreground/background state.
- Android observations are short-lived (5 seconds) and bounded before reaching Context/Planner.
- `ObservationKind.ANDROID_STATE` is persisted through the existing observation schema.
- Added `ACCESS_NETWORK_STATE` because active-network inspection is a normal Android capability and does not require a runtime user prompt.
- Observation adapters never execute tools and never grant capabilities or permissions.

### Android action adapters
- Added `AndroidActionAdapter` contract with explicit precondition and postcondition text.
- Added `ClipboardWriteTool` as a minimal foreground-only Android mutation.
- Clipboard write declares `CLIPBOARD_WRITE`, is mutable, requires `TOOL_EXECUTION` permission and remains subject to Policy/Capability/Sandbox/Verifier/PermissionGate.
- Clipboard verification reads the post-state and only returns CONFIRMED when the observed clipboard exactly matches the requested text; otherwise it returns UNKNOWN or FAILED conservatively.
- Existing HTTPS `open_url` remains controlled and does not gain arbitrary intents.

### Integration
- `AndroidToolHub` exposes the Android state observation adapter and registers the new clipboard action.
- `NexusAgent` consumes Android observations before loop decisions alongside task and plan observations.
- Tool outcomes continue to create verified observations only after explicit verification.

### Safety constraints preserved
- No arbitrary intents, shell or command execution.
- No autonomous permission escalation.
- No background bypass of Android restrictions.
- Android state observation is read-only.
- Android action adapters cannot bypass Policy, Capability, Sandbox, Verifier or PermissionGate.
- UNKNOWN is never treated as SUCCESS.
- Plugins still cannot execute arbitrary code.

### Version
- versionCode 32
- versionName 1.24

## Tests / limitations V1.24
- Static source validation performed for capability registration, observation wiring, version metadata and documentation continuity.
- Verified that `ObservationKind.ANDROID_STATE` is compatible with the existing SQLite observation serialization because the schema stores enum names as TEXT.
- Verified that the clipboard action declares explicit capability, permission, mutability and verification behavior.
- Android build was not executed because this workspace still lacks a usable Gradle Wrapper/Android build environment.
- Runtime validation on a real Android device remains required, especially foreground/background process-state reporting and clipboard behavior across Android versions.

# ROADMAP — PRÓXIMA ETAPA OBRIGATÓRIA

## V1.25 — Android Action Preconditions + Postcondition Verifier
The next AI must continue here.

Objective: make Android actions contract-driven instead of relying mainly on tool-level conventions.

Implement:
- `AndroidActionContract` with structured preconditions and postconditions;
- precondition evaluator that runs before a tool action and can return ALLOW / BLOCK / UNKNOWN;
- postcondition evaluator that consumes verified Android state observations;
- explicit action lifecycle: PRECHECK → AUTHORIZE → EXECUTE → VERIFY → OBSERVE;
- connect action contracts to `AgentLoop` so the loop cannot advance on an unverified Android mutation;
- persist contract results and verification evidence in `AgentJournal`;
- keep all existing Policy, Capability, Sandbox, Verifier and PermissionGate layers authoritative;
- update `GPT.md`, `README.md` and deliver the complete ZIP.

### V1.25 safety constraints
- Preconditions can only restrict execution; they cannot grant permission.
- Postconditions cannot be declared true by the model or tool output alone.
- UNKNOWN remains UNKNOWN.
- No arbitrary Android intents, shell execution or permission escalation.
- No background action may bypass platform restrictions.
- Contract evaluation must be deterministic and auditable.


## V1.25 — Android Action Preconditions + Postcondition Verifier
Implemented from the V1.24 roadmap.

### Architecture changes
- Added `AndroidActionContract` with structured preconditions and postconditions.
- Added `AndroidActionContractEvaluator` with deterministic `ALLOW / BLOCK / UNKNOWN` precheck and `CONFIRMED / FAILED / UNKNOWN` postcheck states.
- `MultiStepExecution` now enforces the Android action lifecycle: `PRECHECK → AUTHORIZE → EXECUTE → VERIFY → OBSERVE`.
- Android prechecks execute before authorization and cannot grant permission.
- Android postchecks consume explicit verification evidence plus the current observation snapshot; model/tool output alone cannot confirm a postcondition.
- `AgentLoop` can only advance after `MultiStepExecution` returns a confirmed execution report, so an Android mutation cannot advance as successful without verification and contract confirmation.
- Added `contract_events` persistence to `AgentJournal` schema version 11, including action, contract id, stage, status, detail and evidence.
- `NexusAgent` passes the Android observation adapter into multi-step execution so contract checks use the same observation source.

### V1.25 safety constraints
- Preconditions can only restrict execution; they cannot grant permission.
- Postconditions cannot be declared true by the model or tool output alone.
- UNKNOWN remains UNKNOWN.
- No arbitrary Android intents, shell execution or permission escalation.
- No background action may bypass platform restrictions.
- Contract evaluation is deterministic and auditable.
- Policy, Capability, Sandbox, Verifier and PermissionGate remain authoritative.

### Version
- versionCode 34
- versionName 1.26

### Tests / limitations V1.25
- Static source validation performed for contract registration, lifecycle logging, journal schema migration and documentation continuity.
- Verified existing V1.24 Android actions continue to implement the contract interface.
- Android build/runtime validation was not executed in this workspace because the project still lacks a usable Gradle Wrapper/Android build environment.
- Real-device testing remains required for Android foreground detection, clipboard behavior and lifecycle behavior across Android versions.

# V1.26 — Android Action Registry + Safe Action Catalog
Implemented from the V1.25 roadmap.

### Architecture changes
- Added `AndroidActionRegistry` as the immutable allowlist for official Android actions known to NEXUS.
- Added `SafeAndroidActionDefinition` with capability, permission, mutability, risk and background metadata.
- Added deterministic catalog validation; every Android action must implement `AndroidActionAdapter` and expose a structured contract.
- Added safe catalog query methods for Planner/Context use without granting authority.
- `AndroidToolHub` now registers Android actions only through the allowlisted registry.
- Existing `device_info`, `open_url` and `clipboard_write` are represented by contract-driven catalog entries.
- Added `catalog_events` to `AgentJournal` schema version 12 for validation/audit events.
- Plugin registration remains separate from the official Android registry; plugins cannot add Android actions to the allowlist.

### V1.26 safety constraints
- The catalog is an allowlist, not a permission grant.
- The model cannot register new Android actions.
- Plugins cannot self-register privileged Android actions.
- A catalog entry without a valid contract is rejected.
- Unsafe capability/contract combinations are rejected.
- No arbitrary intents, shell/command execution or autonomous permission escalation.
- Policy, Capability, Sandbox, Verifier and PermissionGate remain authoritative.
- BUILTIN security rules remain immutable.

### Version
- versionCode 34
- versionName 1.26

### Tests / limitations V1.26
- Static source validation performed for allowlist wiring, contract enforcement, catalog metadata and journal migration.
- ZIP integrity will be checked before delivery.
- Android build/runtime validation was not executed because this workspace still lacks a usable Gradle Wrapper/Android build environment.
- Real-device validation remains required for Android lifecycle, clipboard and external URL behavior.

# ROADMAP — PRÓXIMA ETAPA OBRIGATÓRIA

## V1.27 — Android Action Selection + Intent Safety Gateway
The next AI must continue here.

Objective: let Planner/Context select only from the safe Android catalog and add a final intent-safety gateway immediately before Android execution.

Implement:
- `AndroidActionSelector` that resolves requested action names only against `AndroidActionRegistry`;
- deterministic rejection of unknown/unregistered Android actions;
- `AndroidIntentSafetyGateway` for `open_url` and future external-activity actions;
- strict HTTPS host/scheme validation and explicit external-target metadata;
- no arbitrary action/intent strings from the model;
- catalog lookup/audit events for selection and rejection;
- integrate selection into Planner/AgentLoop without granting authority;
- keep execution behind Policy → Capability → Sandbox → Verifier → PermissionGate → Contract;
- update `GPT.md`, `README.md` and deliver the complete ZIP.

### V1.27 safety constraints
- Unknown actions are rejected closed.
- The model may request a catalog action but cannot create or modify an action definition.
- External intents must use a fixed allowlisted action type and validated HTTPS URI.
- No implicit package targeting, arbitrary extras, shell commands or permission escalation.
- Selection/gateway layers may restrict execution but never grant Android permissions.


# V1.27 — Android Action Selection + Intent Safety Gateway
Implemented from the V1.26 roadmap.

### Architecture changes
- Added `AndroidActionSelector`, resolving Android action requests only against the immutable `AndroidActionRegistry` safe catalog.
- Integrated catalog selection into `NexusAgent` before plan creation and again at multi-step execution, so selection is a restriction layer rather than an authority grant.
- Added `AndroidIntentSafetyGateway` as the final side-effect-free validator for `open_url`. It accepts only HTTPS, rejects embedded credentials, fragments, non-standard ports and local hosts, and never accepts arbitrary Android intent strings.
- Updated the registry-backed `open_url` adapter to pass through the Intent Safety Gateway before creating the fixed `ACTION_VIEW` + `CATEGORY_BROWSABLE` intent.
- Added `action_selection_events` to `AgentJournal` schema version 13 for selection/rejection auditability.
- Main foreground and background worker now construct the selector from the same official Android catalog.

### V1.27 safety constraints
- Unknown Android actions are rejected closed.
- The model may request only a catalog action; it cannot create, modify or register Android actions.
- External URL execution uses a fixed action type and validated HTTPS URI only.
- No arbitrary intents, package targeting, extras, shell commands or permission escalation.
- Selection and gateway layers can only restrict execution; they never grant Android permissions.
- Policy, Capability, Sandbox, Verifier, PermissionGate and Action Contracts remain authoritative.

### Version
- versionCode 35
- versionName 1.27

### Tests / limitations V1.27
- Static source validation performed for selector wiring, gateway validation, journal migration and documentation continuity.
- ZIP integrity checked before delivery.
- Full Android build/runtime validation was not executed because this workspace still lacks a usable Gradle Wrapper/Android build environment.
- Real-device validation remains required for foreground lifecycle, external URL handling and Android version-specific intent behavior.

# ROADMAP — PRÓXIMA ETAPA OBRIGATÓRIA

## V1.28 — Android Action Result Gateway + Post-Action State Verification
The next AI must continue here.

Objective: close the Android action loop by normalizing action results and requiring explicit post-action evidence before an action can be considered confirmed.

Implement:
- `AndroidActionResultGateway` for structured result normalization;
- explicit result states: `EXECUTED`, `REJECTED`, `FAILED`, `UNKNOWN`;
- connect result normalization to `AndroidActionContract` postconditions and `ObservationCoordinator`;
- strengthen `open_url` so “startActivity accepted” remains `UNKNOWN` unless an independent observable signal exists;
- add safe result/audit events to `AgentJournal`;
- preserve fail-closed behavior and no permission escalation;
- update `GPT.md`, `README.md` and deliver the complete ZIP.

### V1.28 safety constraints
- Tool output alone cannot prove a real-world Android post-state.
- UNKNOWN must not be converted into success by the model.
- Result normalization cannot grant permission or bypass Policy/Capability/Sandbox/Verifier/PermissionGate/Contract layers.
- No arbitrary intents or new Android actions.

# V1.28 — Android Action Result Gateway + Post-Action State Verification
Implemented from the V1.27 roadmap.

### Architecture changes
- Added `AndroidActionResultGateway` and explicit result states: `EXECUTED`, `REJECTED`, `FAILED`, `UNKNOWN`.
- Android tool results are normalized immediately after execution and again after post-action verification.
- Added `action_result_events` to `AgentJournal` schema version 14 for normalized result/audit history.
- `open_url` remains conservative: successful `startActivity` acceptance is normalized to `UNKNOWN` when verification cannot independently observe the external app state.
- Verification failures normalize to `FAILED`; explicit policy/permission/catalog rejection is normalized to `REJECTED`.
- Verified outcomes continue to flow into `ObservationCoordinator`; the result gateway itself never creates authority.
- No arbitrary intents, new Android actions, permission escalation or policy bypass was introduced.

### V1.28 safety constraints
- Tool output alone never proves a real-world Android post-state.
- UNKNOWN is terminal for confirmation purposes and cannot be promoted to success by the model.
- Result normalization cannot grant permissions or bypass Policy → Capability → Sandbox → Verifier → PermissionGate → Contract.
- The gateway is a normalization/audit layer, not an authority layer.

### Version
- versionCode 36
- versionName 1.28

### Tests / limitations V1.28
- Static source validation performed for gateway wiring, journal migration and `open_url` UNKNOWN handling.
- ZIP integrity checked before delivery.
- Full Android build/runtime validation was not executed because this workspace still lacks a usable Gradle Wrapper/Android build environment.
- Real-device validation remains required for external URL behavior and Android version-specific lifecycle/intent behavior.

# ROADMAP — PRÓXIMA ETAPA OBRIGATÓRIA

## V1.29 — Android State Observation Adapters + Independent Evidence
The next AI must continue here.

Objective: strengthen post-action verification with independent Android observation sources, without granting the agent additional authority.

Implement:
- `AndroidStateEvidenceSource` abstraction;
- independent evidence adapters for supported safe actions;
- timestamp/source/age metadata for evidence;
- evidence correlation between action result, postcondition and observed state;
- conservative confidence calculation;
- explicit `CONFIRMED` only when the postcondition has sufficient independent evidence;
- preserve `UNKNOWN` when Android cannot expose reliable external state;
- audit evidence provenance and conflicts;
- update `GPT.md`, `README.md` and deliver the complete ZIP.

### V1.29 safety constraints
- Observation is read-only and never grants permission.
- The model cannot manufacture evidence.
- Evidence from the same API call that produced the action result must not be treated as independent evidence.
- Conflicting or stale evidence must reduce confidence or become UNKNOWN.
- No new arbitrary Android intents or privileged APIs.
