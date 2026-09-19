package com.nexus.ai

class MemoryRetriever(private val store: MemoryStore) {
    fun relevant(query: String, limit: Int = 8): List<String> {
        val tokens = query.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 }
            .distinct()
        if (tokens.isEmpty()) return store.recent(limit)
        return tokens.flatMap { store.search(it, limit) }
            .distinct()
            .take(limit)
    }
}
