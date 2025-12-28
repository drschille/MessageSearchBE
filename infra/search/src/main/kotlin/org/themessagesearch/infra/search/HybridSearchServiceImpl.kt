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

    override suspend fun search(query: String, limit: Int, offset: Int, weights: HybridWeights): SearchResponse {
        val vec = embeddingClient.embed(listOf(query)).firstOrNull() ?: FloatArray(embeddingDimension) { 0.0f }
        val vectorLiteral = vec.joinToString(prefix = "[", postfix = "]") { it.toString() }
        val sql = """
            WITH q AS (
                SELECT to_tsvector('simple', ?) AS qts, ?::vector($embeddingDimension) AS qvec
            ),
            text_hits AS (
                SELECT d.id, ts_rank(d.tsv, q.qts) AS text_score
                FROM documents d, q
                WHERE d.tsv @@ q.qts
            ),
            vec_hits AS (
                SELECT e.doc_id AS id, 1 - (e.vec <=> q.qvec) AS vec_score
                FROM doc_embeddings e, q
                ORDER BY e.vec <=> q.qvec
                LIMIT ?
            ),
            scored AS (
                SELECT d.id,
                       d.title,
                       ts_headline('simple', d.body, q.qts, 'MaxFragments=2, MinWords=5, MaxWords=32') AS snippet,
                       coalesce(th.text_score,0) AS text_score,
                       coalesce(vh.vec_score,0)  AS vec_score,
                       (? * coalesce(th.text_score,0) + ? * coalesce(vh.vec_score,0)) AS final_score
                FROM documents d
                LEFT JOIN text_hits th ON th.id = d.id
                LEFT JOIN vec_hits vh ON vh.id = d.id
                WHERE coalesce(th.text_score,0) > 0 OR coalesce(vh.vec_score,0) > 0
            )
            SELECT id, title, snippet, text_score, vec_score, final_score,
                   COUNT(*) OVER() AS total
            FROM scored
            ORDER BY final_score DESC
            LIMIT ? OFFSET ?
        """.trimIndent()
        val ds = DatabaseFactory.getDataSource()
        return ds.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                ps.setString(idx++, query)
                ps.setString(idx++, vectorLiteral)
                ps.setInt(idx++, candidateK)
                ps.setDouble(idx++, weights.text)
                ps.setDouble(idx++, weights.vector)
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
            id = getObject("id").toString(),
            title = getString("title"),
            snippet = getString("snippet"),
            textScore = getDouble("text_score"),
            vectorScore = getDouble("vec_score"),
            finalScore = getDouble("final_score")
        )
    }
}
