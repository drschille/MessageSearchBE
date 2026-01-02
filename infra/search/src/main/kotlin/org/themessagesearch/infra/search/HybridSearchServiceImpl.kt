package org.themessagesearch.infra.search

import org.themessagesearch.core.model.HybridWeights
import org.themessagesearch.core.model.SearchResponse
import org.themessagesearch.core.model.SearchResultItem
import org.themessagesearch.core.ports.HybridSearchService
import org.themessagesearch.core.ports.EmbeddingClient
import org.themessagesearch.infra.db.DatabaseFactory
import java.sql.ResultSet

class HybridSearchServiceImpl(
    private val embeddingClient: EmbeddingClient,
    private val embeddingDimension: Int = 1536,
    private val candidateK: Int = 200
) : HybridSearchService {

    override suspend fun search(query: String, limit: Int, offset: Int, weights: HybridWeights, languageCode: String?): SearchResponse {
        val vec = embeddingClient.embed(listOf(query)).firstOrNull() ?: FloatArray(embeddingDimension) { 0.0f }
        val vectorLiteral = vec.joinToString(prefix = "[", postfix = "]") { it.toString() }
        val sql = """
            WITH q AS (
                SELECT to_tsvector('simple', ?) AS qts, ?::vector($embeddingDimension) AS qvec
            ),
            paragraph_text_hits AS (
                SELECT p.id, ts_rank(p.tsv, q.qts) AS text_score
                FROM document_paragraphs p, q
                WHERE p.tsv @@ q.qts
                  AND (? IS NULL OR p.language_code = ?)
            ),
            paragraph_vec_hits AS (
                SELECT e.paragraph_id AS id, 1 - (e.vec <=> q.qvec) AS vec_score
                FROM paragraph_embeddings e, q
                ORDER BY e.vec <=> q.qvec
                LIMIT ?
            ),
            scored AS (
                SELECT p.id AS paragraph_id,
                       p.document_id,
                       p.language_code,
                       d.title,
                       d.snapshot_id,
                       d.version,
                       ts_headline('simple', p.body, q.qts, 'MaxFragments=2, MinWords=5, MaxWords=32') AS snippet,
                       coalesce(th.text_score,0) AS text_score,
                       coalesce(vh.vec_score,0)  AS vec_score,
                       (? * coalesce(th.text_score,0) + ? * coalesce(vh.vec_score,0)) AS final_score
                FROM document_paragraphs p
                JOIN documents d ON d.id = p.document_id
                LEFT JOIN paragraph_text_hits th ON th.id = p.id
                LEFT JOIN paragraph_vec_hits vh ON vh.id = p.id
                WHERE (coalesce(th.text_score,0) > 0 OR coalesce(vh.vec_score,0) > 0)
                  AND (? IS NULL OR p.language_code = ?)
            )
            SELECT paragraph_id, document_id, language_code, title, snapshot_id, version, snippet, text_score, vec_score, final_score,
                   COUNT(*) OVER() AS total
            FROM scored
            ORDER BY final_score DESC
            LIMIT ? OFFSET ?
        """.trimIndent()
        val ds = DatabaseFactory.getDataSource()
        return ds.connection.use { conn ->
            @Suppress("UNUSED_CHANGED_VALUE")
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                ps.setString(idx++, query)
                ps.setString(idx++, vectorLiteral)
                ps.setString(idx++, languageCode)
                ps.setString(idx++, languageCode)
                ps.setInt(idx++, candidateK)
                ps.setDouble(idx++, weights.text)
                ps.setDouble(idx++, weights.vector)
                ps.setString(idx++, languageCode)
                ps.setString(idx++, languageCode)
                ps.setInt(idx++, limit)
                ps.setInt(idx++, offset)
                val rs = ps.executeQuery()
                val out = mutableListOf<SearchResultItem>()
                var total = 0L
                while (rs.next()) {
                    if (total == 0L) total = rs.getLong("total")
                    rs.toItem(out)
                }
                SearchResponse(
                    total = total,
                    limit = limit,
                    offset = offset,
                    results = out
                )
            }
        }
    }

    private fun ResultSet.toItem(out: MutableList<SearchResultItem>) {
        out += SearchResultItem(
            documentId = getObject("document_id").toString(),
            paragraphId = getObject("paragraph_id").toString(),
            snapshotId = getObject("snapshot_id")?.toString(),
            version = getLong("version"),
            languageCode = getString("language_code"),
            title = getString("title"),
            snippet = getString("snippet"),
            textScore = getDouble("text_score"),
            vectorScore = getDouble("vec_score"),
            finalScore = getDouble("final_score")
        )
    }
}
