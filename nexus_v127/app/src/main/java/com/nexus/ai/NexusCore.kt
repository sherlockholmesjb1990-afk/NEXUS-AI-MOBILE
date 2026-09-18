package com.nexus.ai

class NexusCore(
    private val agent: NexusAgent,
    private val memory: MemoryStore,
    private val registry: ToolRegistry = ToolRegistry(),
    private val journal: AgentJournal? = null
) {
    private val retriever = MemoryRetriever(memory)
    private val contextEngine = journal?.let { ContextEngine(memory, it, registry) }

    fun prepareMessages(text: String, image: ImagePayload? = null): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        val context = contextEngine?.build(text)
        if (context != null) {
            messages += contextEngine.systemMessage(context)
        } else {
            val memoryContext = retriever.relevant(text, 8).joinToString("\n")
            if (memoryContext.isNotBlank()) {
                messages += ChatMessage(MessageRole.SYSTEM, "Memória relevante do NEXUS:\n$memoryContext")
            }
        }
        messages += ChatMessage(MessageRole.USER, text, image)
        return messages
    }

    suspend fun send(text: String, image: ImagePayload? = null): AgentResponse {
        val result = agent.run(prepareMessages(text, image))
        memory.add("conversation", "USER: $text")
        memory.add("conversation", "ASSISTANT: ${result.text}")
        return result
    }

    fun rememberConversation(user: String, assistant: String) {
        memory.add("conversation", "USER: $user")
        memory.add("conversation", "ASSISTANT: $assistant")
    }
}
