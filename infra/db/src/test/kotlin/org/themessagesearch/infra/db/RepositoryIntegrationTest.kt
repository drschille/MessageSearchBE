package org.themessagesearch.infra.db

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.containers.PostgreSQLContainer
import org.themessagesearch.core.model.DocumentCreateRequest
import org.themessagesearch.core.model.DocumentParagraphInput
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
        val doc = docRepo.create(sampleRequest("Migrate", "Check tables"))
        val fetched = docRepo.findById(doc.id, snapshotId = null, languageCode = doc.languageCode)
        assertNotNull(fetched)
        assertEquals(doc.title, fetched!!.title)
    }

    @Test
    fun `document CRUD and missing embedding list`() = runBlocking {
        assumeTrue(postgres != null)
        val doc = docRepo.create(sampleRequest("Title A", "Body A"))
        val missing = docRepo.listParagraphsMissingEmbedding(10)
        val firstParagraph = doc.paragraphs.first()
        assertTrue(missing.any { it.id.value == firstParagraph.id.value })
        // upsert embedding removes from missing
        val vector = FloatArray(1536) { 0f }
        embRepo.upsertParagraphEmbedding(firstParagraph.id, vector)
        val missing2 = docRepo.listParagraphsMissingEmbedding(10)
        assertFalse(missing2.any { it.id.value == firstParagraph.id.value })
    }

    @Test
    fun `batch upsert embeddings`() = runBlocking {
        assumeTrue(postgres != null)
        val paragraphs = (1..3).map { idx ->
            docRepo.create(sampleRequest("Title $idx", "Body $idx")).paragraphs.first()
        }
        val vectors = paragraphs.associate { it.id to randomVector() }
        embRepo.batchUpsertParagraphEmbeddings(vectors)
        paragraphs.forEach { paragraph ->
            assertTrue(embRepo.hasParagraphEmbedding(paragraph.id))
        }
    }

    @Test
    fun `indexes created`() = runBlocking {
        assumeTrue(postgres != null)
        val conn = DatabaseFactory.getDataSource().connection
        conn.use { c ->
            fun hasIndex(table: String, idx: String): Boolean = c.prepareStatement(
                "SELECT 1 FROM pg_indexes WHERE tablename = ? AND indexname = ?"
            ).use { ps ->
                ps.setString(1, table)
                ps.setString(2, idx)
                ps.executeQuery().next()
            }
            assertTrue(hasIndex("documents", "idx_documents_tsv"), "Missing idx_documents_tsv")
            assertTrue(hasIndex("document_paragraphs", "idx_paragraphs_tsv"), "Missing idx_paragraphs_tsv")
            assertTrue(hasIndex("paragraph_embeddings", "idx_paragraph_embeddings_vec"), "Missing idx_paragraph_embeddings_vec")
        }
    }

    @Test
    fun `dimension mismatch throws`() = runBlocking {
        assumeTrue(postgres != null)
        val doc = docRepo.create(sampleRequest("Dim", "Mismatch"))
        val paragraph = doc.paragraphs.first()
        val bad = FloatArray(10) { 0f }
        val ex = assertThrows<IllegalArgumentException> { runBlocking { embRepo.upsertParagraphEmbedding(paragraph.id, bad) } }
        assertTrue(ex.message!!.contains("Vector length"))
    }

    private fun sampleRequest(title: String, body: String, languageCode: String = "en-US"): DocumentCreateRequest {
        val paragraphs = body.split("\n\n").mapIndexed { idx, text ->
            DocumentParagraphInput(position = idx, heading = null, body = text, languageCode = languageCode)
        }
        return DocumentCreateRequest(title = title, languageCode = languageCode, paragraphs = paragraphs)
    }

    private fun randomVector(): FloatArray = FloatArray(1536) { Random.nextFloat() }
}
