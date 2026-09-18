# NEXUS AI MOBILE — V1.25

Infraestrutura pessoal de agente Android construída em camadas.

## V1.18 — Policy Persistence + Admin Console
A V1.18 torna as políticas persistentes e administráveis sem entregar esse poder ao agente.

### Componentes
- `PolicyStore` — SQLite persistente.
- `PolicyRuleRecord` — formato serializável de regra.
- `PolicyRepository` — carrega regras ativas e mantém prioridade determinística.
- `PolicyAdminService` — API administrativa para regras USER.
- `NexusPolicyEngine` — continua sendo o ponto de decisão.
- Console no app — consulta e habilita/desabilita regras USER.
- `AgentJournal` — auditoria das alterações de política.

### Proteções
- BUILTIN não pode ser editada, desativada ou removida.
- USER usa prioridade 1..50 e não pode sobrepor as proteções BUILTIN.
- `DELETE_FILES` não pode receber `ALLOW` por regra USER.
- risco HIGH/CRITICAL não pode ser reduzido a `ALLOW` por regra USER.
- O agente não recebe `PolicyAdminService`.

### Fluxo
```text
Intent → Context → Goal → Planner → PolicyRepository → PolicyEngine → Capability/Sandbox → Verifier → Permission → Execute → Verify → Recovery → Audit
```

Leia `GPT.md` antes de continuar o desenvolvimento.

**Próxima etapa obrigatória: V1.19 — Policy Editor + Policy Simulation.**

## V1.19 — Policy Editor + Policy Simulation
A V1.19 adiciona edição administrativa segura de regras USER, simulação explicável de decisões e histórico persistente com rollback seguro. A simulação nunca executa ferramentas.

### Segurança
- BUILTIN protegida.
- Regras USER continuam limitadas a prioridade 1..50.
- Proteções críticas não podem virar ALLOW.
- Rollback passa pelas mesmas validações da criação/edição.
- Agente não recebe acesso administrativo.

### Próxima etapa
**V1.21 — Context-aware Policy + Risk Scoring**: enriquecer a decisão de política com score determinístico de risco e explicação dos fatores, mantendo default-deny e as proteções BUILTIN.

## V1.21 — Context-aware Policy + Risk Scoring
The Policy Engine now evaluates a deterministic 0–100 operational RiskScore using capability risk, mutability, background execution, permission sensitivity, request origin, optional verification confidence and explicitly supplied user state. Scoring is restrictive-only and cannot weaken critical protections. Policy simulation exposes the score and factors without executing tools.

Next: **V1.21 — Goal Intelligence + State/Observation Foundation**.


## V1.21 — Goal Intelligence + Observations
The NEXUS now enriches persistent goals with priority, optional deadlines and success criteria, and maintains a bounded, read-only observation layer for goal/task state. Observations are injected into planning context with explicit source and confidence. Unknown state remains unknown.

Next mandatory milestone: **V1.22 — State/Observation Adapters + Tool Outcome Integration**.

## V1.22 — State/Observation Adapters
The observation layer now receives read-only evidence from persisted task state, plan/checkpoints and explicitly verified tool outcomes. Tool output is evidence only; it does not become a confirmed observation without `VerificationResult`. Stale evidence is filtered and conflicting evidence is conservatively represented as `UNKNOWN` unless a trusted verified-tool observation resolves it.

### Next
**V1.23 — Agent Loop: Observe → Decide → Act → Verify**. The loop will be bounded, checkpointed and forced through the existing security pipeline.

## V1.23 — Agent Loop
V1.23 adds an explicit bounded Observe → Decide → Act → Verify → Recover loop controller. Each cycle consumes a bounded observation snapshot before the decision, while execution remains behind Policy, Capability, Sandbox, Verifier and PermissionGate. The loop has finite iterations, cancellation checks, terminal states and a bounded read-only trace.

**Next:** V1.24 — Android State Observation + Action Adapters.

## V1.24 — Android State + Action Adapters
- Read-only Android state observations: battery, network, screen and app process state.
- Foreground-only clipboard write action with explicit capability and postcondition verification.
- Android actions now expose precondition/postcondition contracts at the tool boundary.
- Existing Policy → Capability → Sandbox → Verifier → PermissionGate pipeline remains authoritative.
- Next required milestone: **V1.25 — Android Action Preconditions + Postcondition Verifier**.


## V1.25 — Android Action Preconditions + Postcondition Verifier
A V1.25 transforma ações Android em contratos explícitos e auditáveis.

### Componentes
- `AndroidActionContract` — contrato estruturado por ação.
- `AndroidPrecondition` / `AndroidPostcondition` — condições declarativas.
- `AndroidActionContractEvaluator` — avaliação determinística e sem efeitos colaterais.
- `contract_events` — persistência de prechecks e postchecks no `AgentJournal`.
- `MultiStepExecution` — lifecycle PRECHECK → AUTHORIZE → EXECUTE → VERIFY → OBSERVE.

### Regras
- Preconditions somente restringem; nunca concedem autorização.
- Postconditions só podem ser confirmadas após verificação explícita.
- `UNKNOWN` permanece `UNKNOWN`.
- O loop só recebe sucesso de uma ação Android quando o contrato e a verificação estão confirmados.
- Policy, Capability, Sandbox, Verifier e PermissionGate continuam soberanos.
- Nenhum intent arbitrário, shell ou escalada de permissão foi adicionado.

## Próxima etapa
**V1.26 — Android Action Registry + Safe Action Catalog**: formalizar um catálogo de ações Android suportadas, contratos versionados, capacidades mínimas, preconditions/postconditions reutilizáveis e testes determinísticos antes de ampliar o conjunto de ações reais.


## V1.26 — Android Action Registry + Safe Action Catalog
V1.26 centralizes the official Android action allowlist. Android tools are registered only through `AndroidActionRegistry`, which requires a valid `AndroidActionAdapter` contract and exposes safe metadata for planning/context inspection. Catalog validation is auditable via `AgentJournal` schema 12. Plugins remain unable to self-register privileged Android actions.

**Next:** V1.28 — Android Action Selection + Intent Safety Gateway.


## V1.28 — Android Action Selection + Intent Safety Gateway
V1.28 adds a safe catalog selector and a final HTTPS intent-safety gateway. Android actions must exist in the immutable catalog, and `open_url` accepts only validated HTTPS destinations before the fixed external `ACTION_VIEW` intent is created. Selection/rejection is audited in SQLite schema V13.

Next mandatory step: **V1.28 — Android Action Result Gateway + Post-Action State Verification**.
