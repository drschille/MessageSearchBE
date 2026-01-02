package org.themessagesearch.app

import org.themessagesearch.core.model.DocumentId
import org.themessagesearch.core.model.ReviewId
import org.themessagesearch.core.model.SnapshotId
import org.themessagesearch.core.model.UserId
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.Instant

data class WebhookConfig(
    val reviewSubmittedUrl: String? = null,
    val documentPublishedUrl: String? = null
)

class WebhookNotifier(private val config: WebhookConfig, private val logger: (String) -> Unit) {
    fun notifyReviewSubmitted(documentId: DocumentId, reviewId: ReviewId, summary: String, actorId: UserId) {
        val url = config.reviewSubmittedUrl ?: return
        val payload = """
            {"event":"review.submitted","document_id":"${documentId.value}","review_id":"${reviewId.value}","summary":"${escape(summary)}","actor_id":"${actorId.value}","submitted_at":"${Instant.now()}"}
        """.trimIndent()
        postJson(url, payload)
    }

    fun notifyDocumentPublished(documentId: DocumentId, snapshotId: SnapshotId?, summary: String?, actorId: UserId) {
        val url = config.documentPublishedUrl ?: return
        val snapshotValue = snapshotId?.value ?: ""
        val summaryValue = summary?.let { escape(it) } ?: ""
        val payload = """
            {"event":"document.published","document_id":"${documentId.value}","snapshot_id":"$snapshotValue","summary":"$summaryValue","actor_id":"${actorId.value}","published_at":"${Instant.now()}"}
        """.trimIndent()
        postJson(url, payload)
    }

    private fun postJson(targetUrl: String, payload: String) {
        runCatching {
            val conn = targetUrl.toUrl().openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(payload.toByteArray()) }
            conn.inputStream.use { it.readBytes() }
            conn.disconnect()
        }.onFailure { ex ->
            logger("Webhook delivery failed for $targetUrl: ${ex.message}")
        }
    }

    private fun escape(raw: String): String =
        raw.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun String.toUrl(): URL = URI(this).toURL()
}
