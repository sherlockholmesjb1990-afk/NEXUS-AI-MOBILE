import "dotenv/config";
import express from "express";
import OpenAI from "openai";

const app = express();
app.use(express.json({ limit: "12mb" }));

const port = Number(process.env.PORT || 8787);
const client = new OpenAI({ apiKey: process.env.OPENAI_API_KEY });
const model = process.env.OPENAI_MODEL || "gpt-5.6-luna";

function authorized(req) {
  const expected = process.env.NEXUS_APP_KEY;
  return !expected || req.header("X-Nexus-App-Key") === expected;
}

function makeTools(tools = []) {
  return tools.map(t => ({
    type: "function",
    name: t.name,
    description: t.description,
    parameters: t.parameters || { type: "object", properties: {}, additionalProperties: false },
    strict: true
  }));
}

function makeInput(messages = []) {
  return messages.map(m => {
    const content = [];
    if (m.text) content.push({ type: "input_text", text: m.text });
    if (m.image?.base64) {
      const mime = m.image.mime || "image/jpeg";
      content.push({
        type: "input_image",
        image_url: `data:${mime};base64,${m.image.base64}`,
        detail: "auto"
      });
    }
    return { role: m.role, content };
  });
}

function extract(response) {
  const calls = (response.output || [])
    .filter(x => x.type === "function_call")
    .map(x => ({
      id: x.call_id || x.id,
      name: x.name,
      arguments: x.arguments || "{}"
    }));

  const sources = [];
  for (const item of (response.output || [])) {
    if (item.type === "web_search_call") {
      const action = item.action || {};
      for (const source of (action.sources || [])) {
        if (source?.url && !sources.some(s => s.url === source.url)) {
          sources.push({ url: source.url });
        }
      }
    }
  }

  return {
    reply: response.output_text || "",
    tool_calls: calls,
    response_id: response.id,
    web_used: sources.length > 0,
    sources
  };
}

function responseTools(tools = [], webSearch = false) {
  const result = makeTools(tools);
  if (webSearch) result.unshift({ type: "web_search" });
  return result;
}

app.get("/health", (_req, res) =>
  res.json({ ok: true, service: "nexus-gateway", version: "1.9" })
);

app.post("/v1/agent", async (req, res) => {
  if (!authorized(req)) return res.status(401).json({ error: "unauthorized" });

  try {
    const { messages = [], tools = [], web_search = false } = req.body || {};
    const response = await client.responses.create({
      model,
      store: false,
      input: makeInput(messages),
      tools: responseTools(tools, Boolean(web_search))
    });
    res.json(extract(response));
  } catch (error) {
    console.error(error);
    res.status(500).json({ error: error.message || "gateway_error" });
  }
});

app.post("/v1/agent/continue", async (req, res) => {
  if (!authorized(req)) return res.status(401).json({ error: "unauthorized" });

  try {
    const { response_id, tool_results = [], tools = [], web_search = false } = req.body || {};
    if (!response_id) return res.status(400).json({ error: "response_id_required" });

    const input = tool_results.map(r => ({
      type: "function_call_output",
      call_id: r.call_id,
      output: String(r.output ?? "")
    }));

    const response = await client.responses.create({
      model,
      store: false,
      previous_response_id: response_id,
      input,
      tools: responseTools(tools, Boolean(web_search))
    });

    res.json(extract(response));
  } catch (error) {
    console.error(error);
    res.status(500).json({ error: error.message || "gateway_continue_error" });
  }
});


app.post("/v1/stream", async (req, res) => {
  if (!authorized(req)) return res.status(401).json({ error: "unauthorized" });

  res.status(200);
  res.setHeader("Content-Type", "text/event-stream; charset=utf-8");
  res.setHeader("Cache-Control", "no-cache, no-transform");
  res.setHeader("Connection", "keep-alive");
  res.flushHeaders?.();

  try {
    const { messages = [] } = req.body || {};
    const stream = await client.responses.create({
      model,
      store: false,
      input: makeInput(messages),
      stream: true
    });

    for await (const event of stream) {
      if (event.type === "response.output_text.delta") {
        res.write(`data: ${JSON.stringify({ text: event.delta })}\n\n`);
      } else if (event.type === "response.completed") {
        res.write(`data: ${JSON.stringify({ done: true, response_id: event.response?.id || null })}\n\n`);
      } else if (event.type === "error") {
        res.write(`data: ${JSON.stringify({ error: event.message || "stream_error" })}\n\n`);
      }
    }
    res.write("data: [DONE]\n\n");
    res.end();
  } catch (error) {
    res.write(`data: ${JSON.stringify({ error: error.message || "gateway_stream_error" })}\n\n`);
    res.end();
  }
});

app.listen(port, () => console.log(`NEXUS gateway listening on ${port}`));
