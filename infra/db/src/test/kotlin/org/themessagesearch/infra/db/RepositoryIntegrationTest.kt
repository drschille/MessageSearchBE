package org.themessagesearch.infra.db

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.containers.PostgreSQLContainer
import org.themessagesearch.core.model.Document
import org.themessagesearch.core.model.DocumentId
import org.themessagesearch.infra.db.repo.ExposedDocumentRepository
import org.themessagesearch.infra.db.repo.ExposedEmbeddingRepository
import kotlinx.coroutines.runBlocking

@Disabled("Requires Docker/Testcontainers; enable locally by removing @Disabled")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RepositoryIntegrationTest {
    class PgVectorContainer : PostgreSQLContainer<PgVectorContainer>("ankane/pgvector:latest")

    companion object {
        @Container
        private val postgres = PgVectorContainer().apply {
            withDatabaseName("app")
            withUsername("postgres")
            withPassword("postgres")
            start()
        }
    }

    private val docRepo by lazy { ExposedDocumentRepository() }
    private val embRepo by lazy { ExposedEmbeddingRepository() }

    init {
        // Initialize database + migrations once
        DatabaseFactory.init(
            DatabaseFactory.DbConfig(
                url = postgres.jdbcUrl,
                user = postgres.username,
                password = postgres.password
            )
        )
    }

    @Test
    fun `insert and fetch document`() = runBlocking {
        val doc = Document(DocumentId.random(), "Title", "Body text for testing")
        docRepo.insert(doc)
        val fetched = docRepo.findById(doc.id)
        assertNotNull(fetched)
        assertEquals(doc.title, fetched!!.title)
        assertEquals(doc.body, fetched.body)
    }
}
