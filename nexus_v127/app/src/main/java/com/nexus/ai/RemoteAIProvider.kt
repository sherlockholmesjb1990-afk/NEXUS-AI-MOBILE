package com.nexus.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class RemoteAIProvider(
    private val endpoint: String,
    private val appKey: String? = null
) : AIProvider, StreamingProvider {
    private val client = OkHttpClient()

    private fun toolsJson(tools: List<NexusToolDefinition>): JSONArray {
        val t = JSONArray()
        tools.forEach {
            t.put(JSONObject()
                .put("name", it.name)
                .put("description", it.description)
                .put("parameters", JSONObject(it.parametersJson))
                .put("read_only", it.readOnly))
        }
        return t
    }

    private fun call(payload: JSONObject, url: String = endpoint): AgentResponse {
        val reqBuilder = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
        appKey?.takeIf { it.isNotBlank() }?.let {
            reqBuilder.header("X-Nexus-App-Key", it)
        }

        client.newCall(reqBuilder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Gateway ${response.code}: $body")
            val json = JSONObject(body)
            val calls = mutableListOf<ToolCall>()
            val arr = json.optJSONArray("tool_calls") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                calls += ToolCall(
                    c.optString("id"),
                    c.optString("name"),
                    c.optString("arguments", "{}")
                )
            }
            return AgentResponse(
                text = json.optString("reply"),
                toolCalls = calls,
                responseId = json.optString("response_id").takeIf { it.isNotBlank() }
            )
        }
    }

    override suspend fun respond(
        messages: List<ChatMessage>,
        tools: List<NexusToolDefinition>,
        webSearch: Boolean
    ): AgentResponse = withContext(Dispatchers.IO) {
        val payload = JSONObject()
        if (webSearch) payload.put("web_search", true)
        val arr = JSONArray()
        messages.forEach { m ->
            val o = JSONObject()
                .put("role", m.role.name.lowercase())
                .put("text", m.text)
            m.image?.let {
                o.put("image", JSONObject()
                    .put("mime", it.mime)
                    .put("base64", it.base64))
            }
            arr.put(o)
        }
        payload.put("messages", arr)
        payload.put("tools", toolsJson(tools))
        call(payload)
    }

    override suspend fun continueWithToolResults(
        responseId: String,
        results: List<ToolResult>,
        tools: List<NexusToolDefinition>,
        webSearch: Boolean
    ): AgentResponse = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("response_id", responseId)
            .put("tools", toolsJson(tools))
        if (webSearch) payload.put("web_search", true)

        val arr = JSONArray()
        results.forEach {
            arr.put(JSONObject()
                .put("call_id", it.callId)
                .put("output", it.output))
        }
        payload.put("tool_results", arr)
        call(payload, endpoint + "/continue")
    }

    override fun streamText(messages: List<ChatMessage>): Flow<String> = flow {
        val payload = JSONObject().put("messages", JSONArray().apply {
            messages.forEach { m ->
                put(JSONObject().apply {
                    put("role", m.role.name.lowercase())
                    put("text", m.text)
                    m.image?.let { put("image", JSONObject().put("mime", it.mime).put("base64", it.base64)) }
                })
            }
        })
        val url = endpoint.substringBeforeLast("/agent") + "/stream"
        val reqBuilder = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept", "text/event-stream")
        appKey?.takeIf { it.isNotBlank() }?.let { reqBuilder.header("X-Nexus-App-Key", it) }
        client.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) error("Gateway ${response.code}: ${response.body?.string().orEmpty()}")
            val reader = response.body?.charStream()?.buffered()?.let { java.io.BufferedReader(it) }
                ?: error("Resposta de streaming vazia")
            reader.use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.startsWith("data:")) {
                        val data = line.removePrefix("data:").trim()
                        if (data.isNotEmpty() && data != "[DONE]") {
                            val json = runCatching { JSONObject(data) }.getOrNull()
                            json?.optString("text")?.takeIf { it.isNotEmpty() }?.let { emit(it) }
                            json?.optString("error")?.takeIf { it.isNotEmpty() }?.let { error(it) }
                        }
                    }
                }
            }
        }
    }

}
