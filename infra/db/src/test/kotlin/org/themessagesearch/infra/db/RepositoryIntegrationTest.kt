package org.themessagesearch.infra.db

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.containers.PostgreSQLContainer
import org.themessagesearch.core.model.Document
import org.themessagesearch.core.model.DocumentId
import org.themessagesearch.infra.db.repo.ExposedDocumentRepository
import org.themessagesearch.infra.db.repo.ExposedEmbeddingRepository
import kotlinx.coroutines.runBlocking
import kotlin.random.Random

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RepositoryIntegrationTest {
    private var postgres: PostgreSQLContainer<*>? = null
    private val docRepo = ExposedDocumentRepository()
    private val embRepo = ExposedEmbeddingRepository()

    @BeforeAll
    fun startContainer() {
        // Allow skipping via env or absence of Docker
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
    }

    @AfterAll
    fun stopContainer() {
        postgres?.stop()
    }

    @Test
    fun `migration V1 created tables`() = runBlocking {
        assumeTrue(postgres != null)
        // Simple sanity: insert + select to prove tables exist
        val doc = Document(DocumentId.random(), "Migrate", "Check tables")
        docRepo.insert(doc)
        val fetched = docRepo.findById(doc.id)
        assertNotNull(fetched)
        assertEquals(doc.title, fetched!!.title)
    }

    @Test
    fun `document CRUD and missing embedding list`() = runBlocking {
        assumeTrue(postgres != null)
        val doc = Document(DocumentId.random(), "Title A", "Body A")
        docRepo.insert(doc)
        val missing = docRepo.listIdsMissingEmbedding(10)
        assertTrue(missing.any { it.value == doc.id.value })
        // upsert embedding removes from missing
        val vector = FloatArray(1536) { 0f }
        embRepo.upsertEmbedding(doc.id, vector)
        val missing2 = docRepo.listIdsMissingEmbedding(10)
        assertFalse(missing2.any { it.value == doc.id.value })
    }

    @Test
    fun `batch upsert embeddings`() = runBlocking {
        assumeTrue(postgres != null)
        val docs = (1..3).map { idx ->
            Document(DocumentId.random(), "Title $idx", "Body $idx")
        }
        docs.forEach { docRepo.insert(it) }
        val vectors = docs.associate { it.id to randomVector() }
        embRepo.batchUpsertEmbeddings(vectors)
        docs.forEach { id -> assertTrue(embRepo.hasEmbedding(id.id)) }
    }

    private fun randomVector(): FloatArray = FloatArray(1536) { Random.nextFloat() }
}
