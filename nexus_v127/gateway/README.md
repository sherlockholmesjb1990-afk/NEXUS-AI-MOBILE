# NEXUS Gateway V1.3

Backend Node/Express que mantém a chave do provedor fora do APK.

## Rotas
- `GET /health`
- `POST /v1/agent` — agente + ferramentas
- `POST /v1/agent/continue` — continuação após ferramentas
- `POST /v1/stream` — streaming SSE de texto

## Streaming
A rota `/v1/stream` traduz os eventos semânticos de streaming da Responses API para SSE simples para o Android. O gateway escuta `response.output_text.delta`, `response.completed` e `error`.

## Configuração
Copie `.env.example` para `.env` e defina `OPENAI_API_KEY`.
Nunca coloque a chave do provedor no aplicativo Android.
