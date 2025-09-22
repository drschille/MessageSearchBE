package org.themessagesearch.infra.ai

import org.themessagesearch.core.ports.EmbeddingClient
import org.themessagesearch.core.ports.ChatClient

class StubEmbeddingClient(private val dim: Int = 1536) : EmbeddingClient {
    override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map { FloatArray(dim) { 0.0f } }
}

class StubChatClient : ChatClient {
    override suspend fun generate(prompt: String, context: List<String>): String =
        "Stub answer referencing docs: ${context.take(5).joinToString()}"
}

