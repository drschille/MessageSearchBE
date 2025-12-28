package org.themessagesearch.core.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class DocumentId(val value: String) {
    init { require(runCatching { UUID.fromString(value) }.isSuccess) { "Invalid UUID: $value" } }
    companion object { fun random() = DocumentId(UUID.randomUUID().toString()) }
    override fun toString(): String = value
}

@Serializable
data class ParagraphId(val value: String) {
    init { require(runCatching { UUID.fromString(value) }.isSuccess) { "Invalid UUID: $value" } }
    companion object { fun random() = ParagraphId(UUID.randomUUID().toString()) }
    override fun toString(): String = value
}

@Serializable
data class SnapshotId(val value: String) {
    init { require(runCatching { UUID.fromString(value) }.isSuccess) { "Invalid UUID: $value" } }
    companion object { fun random() = SnapshotId(UUID.randomUUID().toString()) }
    override fun toString(): String = value
}

@Serializable
data class DocumentParagraph(
    val id: ParagraphId,
    val documentId: DocumentId,
    val position: Int,
    val heading: String? = null,
    val body: String,
    val languageCode: String,
    val createdAt: Instant,
    val updatedAt: Instant
)

@Serializable
data class Document(
    val id: DocumentId,
    val title: String,
    val body: String,
    val version: Long,
    val languageCode: String,
    val paragraphs: List<DocumentParagraph>,
    val snapshotId: SnapshotId? = null,
    val createdAt: Instant,
    val updatedAt: Instant
)

@Serializable
data class DocumentParagraphInput(
    val position: Int,
    val heading: String? = null,
    val body: String,
    val languageCode: String
)

@Serializable
data class DocumentCreateRequest(
    val title: String,
    val languageCode: String,
    val paragraphs: List<DocumentParagraphInput>,
    val publish: Boolean = true
)

@Serializable
data class DocumentParagraphResponse(
    val id: String,
    val documentId: String,
    val position: Int,
    val heading: String? = null,
    val body: String,
    val languageCode: String,
    val createdAt: Instant,
    val updatedAt: Instant
)

@Serializable
data class DocumentResponse(
    val id: String,
    val title: String,
    val body: String,
    val version: Long,
    val snapshotId: String? = null,
    val languageCode: String,
    val paragraphs: List<DocumentParagraphResponse>,
    val createdAt: Instant,
    val updatedAt: Instant
)

@Serializable
data class SearchRequest(
    val query: String,
    val limit: Int? = null,
    val offset: Int? = null,
    val weights: HybridWeights? = null,
    val languageCode: String? = null
)

@Serializable
data class SearchResultItem(
    val documentId: String,
    val paragraphId: String? = null,
    val snapshotId: String? = null,
    val languageCode: String? = null,
    val title: String,
    val snippet: String? = null,
    val textScore: Double,
    val vectorScore: Double,
    val finalScore: Double
)

@Serializable
data class SearchResponse(
    val total: Long,
    val limit: Int,
    val offset: Int,
    val results: List<SearchResultItem>
)

@Serializable
data class AnswerRequest(val query: String, val limit: Int? = null, val languageCode: String? = null)

@Serializable
data class Citation(
    val documentId: String,
    val snapshotId: String? = null,
    val paragraphId: String? = null,
    val languageCode: String? = null,
    val score: Double,
    val excerpt: String? = null
)

@Serializable
data class AnswerResponse(
    val answer: String,
    val citations: List<Citation>,
    val tokensUsed: TokensUsed? = null
)

@Serializable
data class TokensUsed(val prompt: Int, val completion: Int)

@Serializable
data class EmbedBackfillRequest(val batchSize: Int? = null, val paragraphCursor: String? = null, val languageCode: String? = null)

@Serializable
data class BackfillResult(val processed: Int, val nextParagraphCursor: String?)

@Serializable
data class HybridWeights(val text: Double, val vector: Double) {
    init {
        require(text >= 0 && vector >= 0) { "Weights must be non-negative" }
        require(text > 0 || vector > 0) { "At least one weight must be greater than zero" }
    }

    companion object {
        val Default = HybridWeights(text = 0.35, vector = 0.65)
    }
}

fun Document.toResponse(): DocumentResponse = DocumentResponse(
    id = id.value,
    title = title,
    body = body,
    version = version,
    snapshotId = snapshotId?.value,
    languageCode = languageCode,
    paragraphs = paragraphs.map {
        DocumentParagraphResponse(
            id = it.id.value,
            documentId = it.documentId.value,
            position = it.position,
            heading = it.heading,
            body = it.body,
            languageCode = it.languageCode,
            createdAt = it.createdAt,
            updatedAt = it.updatedAt
        )
    },
    createdAt = createdAt,
    updatedAt = updatedAt
)
