package org.themessagesearch.infra.search

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.containers.PostgreSQLContainer
import org.themessagesearch.core.model.DocumentCreateRequest
import org.themessagesearch.core.model.HybridWeights
import org.themessagesearch.core.ports.EmbeddingClient
import org.themessagesearch.infra.db.DatabaseFactory
import org.themessagesearch.infra.db.repo.DocEmbeddingsTable
import org.themessagesearch.infra.db.repo.DocumentsTable
import org.themessagesearch.infra.db.repo.ExposedDocumentRepository
import org.themessagesearch.infra.db.repo.ExposedEmbeddingRepository

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HybridSearchServiceIntegrationTest {
    private var postgres: PostgreSQLContainer<*>? = null
    private val docRepo = ExposedDocumentRepository()
    private val embRepo = ExposedEmbeddingRepository()
    private val embeddingClient = StubEmbeddingClient(1536)
    private lateinit var search: HybridSearchServiceImpl

    @BeforeAll
    fun startContainer() {
        val skip = System.getenv("SKIP_DB_TESTS") == "true"
        assumeTrue(!skip, "SKIP_DB_TESTS=true set; skipping")
        try {
            postgres = PostgreSQLContainer("ankane/pgvector:latest").apply {
                withDatabaseName("app")
                withUsername("postgres")
                withPassword("postgres")
                start()
            }
        } catch (ex: Exception) {
            assumeTrue(false, "Docker unavailable: ${ex.message}")
        }
        val p = postgres!!
        DatabaseFactory.init(DatabaseFactory.DbConfig(p.jdbcUrl, p.username, p.password))
        search = HybridSearchServiceImpl(embeddingClient)
    }

    @AfterAll
    fun stopContainer() {
        postgres?.stop()
    }

    @BeforeEach
    fun cleanTables() {
        transaction(DatabaseFactory.get()) {
            DocEmbeddingsTable.deleteAll()
            DocumentsTable.deleteAll()
        }
    }

    @Test
    fun `text query returns matching document`() = runBlocking {
        assumeTrue(postgres != null)
        docRepo.create(
            DocumentCreateRequest(
                title = "Searchable doc",
                body = "The fox jumps over the lazy dog"
            )
        )
        embeddingClient.vector = FloatArray(embeddingClient.dimension)

        val resp = search.search("fox", limit = 5, offset = 0, weights = HybridWeights.Default)

        assertEquals(1, resp.total)
        val result = resp.results.single()
        assertTrue(result.textScore > 0.0, "Expected positive text score")
    }

    @Test
    fun `vector query surfaces embedding match`() = runBlocking {
        assumeTrue(postgres != null)
        val doc = docRepo.create(
            DocumentCreateRequest(
                title = "Vector doc",
                body = "content that does not matter for vector search"
            )
        )
        val vector = FloatArray(embeddingClient.dimension) { idx -> if (idx == 0) 1f else 0f }
        embRepo.upsertEmbedding(doc.id, vector)
        embeddingClient.vector = vector

        val resp = search.search(
            query = "nonsense",
            limit = 5,
            offset = 0,
            weights = HybridWeights(text = 0.0, vector = 1.0)
        )

        assertEquals(1, resp.total)
        val result = resp.results.single()
        assertEquals(doc.id.value, result.id)
        assertTrue(result.vectorScore > 0.99, "Expected strong vector similarity")
    }

    private class StubEmbeddingClient(val dimension: Int) : EmbeddingClient {
        var vector: FloatArray = FloatArray(dimension)
        override suspend fun embed(texts: List<String>): List<FloatArray> = listOf(vector)
    }
}
