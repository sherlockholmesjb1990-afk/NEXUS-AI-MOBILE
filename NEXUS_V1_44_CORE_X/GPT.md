# NEXUS AI MOBILE — HANDOFF V1.43

## Estado atual
V1.43 — Cycle Resume Isolation.

## Objetivo desta versão
Corrigir a limitação estrutural identificada na V1.42: o registro `tasks` tem `id` como chave primária e, portanto, não deve ser usado como único armazenamento de checkpoint quando existem ciclos A/B independentes para o mesmo `taskId`.

## O que foi feito
1. Schema SQLite atualizado de 19 para 20.
2. Criada tabela `cycle_resume_anchors`:
   - `task_id`
   - `cycle_id`
   - `response_id`
   - `checkpoint`
   - `updated_at`
   - PK composta `(task_id, cycle_id)`
3. `AgentJournal.checkpoint()` grava o anchor por ciclo.
4. `AgentJournal.cycleResumeAnchor()` recupera apenas o ciclo solicitado.
5. `NexusAgent` passa a consultar o anchor por ciclo antes do fallback legado.
6. `NexusResumeABTest` ampliado.
7. VersionCode 50 / versionName 1.43.

## Testes
Ainda NÃO compilados/executados. O projeto não possui Gradle Wrapper.

## Regra de honestidade
Não marcar build/test como PASS até haver saída real do Gradle.

## Próxima etapa obrigatória
V1.44 deve ser BUILD RECOVERY/AUDIT:
- restaurar Gradle Wrapper;
- identificar JDK/Gradle/AGP/Kotlin compatíveis;
- executar `gradle test`;
- executar `assembleDebug`;
- corrigir erros reais;
- auditar os dois `TaskController.kt`;
- validar a configuração do modelo do gateway contra documentação oficial da OpenAI antes de alterar o nome.

## Não fazer ainda
Não adicionar novas funcionalidades de agente antes do primeiro build real.


# CHECKPOINT V1.44 — BUILD RECOVERY / AUDIT

## Estado
- Versão: V1.44 / versionCode 51.
- Prioridade mudou de novas funcionalidades para verificação real do projeto.
- Removida `com.nexus.ai.agent.TaskController`, que não possui referências no código atual. A implementação ativa é `com.nexus.ai.TaskController`.
- CI configurado para instalar Gradle 8.9, gerar o Gradle Wrapper durante o job e executar `./gradlew test` e `./gradlew :app:assembleDebug`.

## Não comprovado ainda
- Build local.
- Testes unitários reais.
- APK real.

## Correção importante
A revisão anterior classificou `gpt-5.6-luna` como modelo inexistente. Isso foi incorreto: a documentação atual da OpenAI lista `gpt-5.6-luna` como Model ID. A configuração do gateway foi preservada.

## Próxima IA
1. Executar o CI ou fornecer ambiente com Gradle.
2. Rodar `./gradlew test --stacktrace`.
3. Corrigir o primeiro erro reproduzível, sem fazer refatorações especulativas.
4. Rodar `./gradlew :app:assembleDebug --stacktrace`.
5. Só depois avançar para nova funcionalidade.
