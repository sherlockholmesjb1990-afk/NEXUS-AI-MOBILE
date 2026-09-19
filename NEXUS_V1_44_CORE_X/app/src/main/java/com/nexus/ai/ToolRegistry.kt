package com.nexus.ai

class ToolRegistry {
    private val tools = linkedMapOf<String, NexusTool>()
    fun register(tool: NexusTool) { tools[tool.definition.name] = tool }
    fun definitions(): List<NexusToolDefinition> = tools.values.map { it.definition }
    fun get(name: String): NexusTool? = tools[name]
    fun remove(name: String) { tools.remove(name) }
    fun clear() = tools.clear()
}
