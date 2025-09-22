package org.themessagesearch.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class DocumentId(val value: String) {
    init { require(runCatching { UUID.fromString(value) }.isSuccess) { "Invalid UUID: $value" } }
    companion object { fun random() = DocumentId(UUID.randomUUID().toString()) }
}

@Serializable
data class Document(
    val id: DocumentId,
    val title: String,
    val body: String
)

@Serializable
data class DocumentCreateRequest(val title: String, val body: String)
@Serializable
data class DocumentResponse(val id: String, val title: String, val body: String)

@Serializable
data class SearchRequest(val query: String, val limit: Int? = null)
@Serializable
data class SearchResultItem(
    val id: String,
    val title: String,
    val textScore: Double,
    val vectorScore: Double,
    val finalScore: Double
)
@Serializable
data class SearchResponse(val results: List<SearchResultItem>)

@Serializable
data class AnswerRequest(val query: String)
@Serializable
data class AnswerResponse(val answer: String, val citations: List<String>)

@Serializable
data class EmbedBackfillRequest(val batchSize: Int? = null)

@Serializable
data class HybridWeights(val text: Double, val vector: Double) {
    init { require(text >= 0 && vector >= 0) { "Weights must be non-negative" } }
}

