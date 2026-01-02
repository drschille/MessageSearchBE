package org.themessagesearch.infra.db

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.containers.PostgreSQLContainer
import org.themessagesearch.core.model.CollaborationSnapshot
import org.themessagesearch.core.model.CollaborationUpdate
import org.themessagesearch.core.model.DocumentCreateRequest
import org.themessagesearch.core.model.DocumentParagraphInput
import org.themessagesearch.core.model.DocumentAuditAction
import org.themessagesearch.core.model.DocumentWorkflowState
import org.themessagesearch.core.model.SnapshotState
import org.themessagesearch.core.model.UserAuditAction
import org.themessagesearch.core.model.UserCreateRequest
import org.themessagesearch.core.model.UserId
import org.themessagesearch.core.model.UserRole
import org.themessagesearch.core.model.UserStatus
import org.themessagesearch.infra.db.repo.ExposedDocumentRepository
import org.themessagesearch.infra.db.repo.ExposedEmbeddingRepository
import org.themessagesearch.infra.db.repo.ExposedCollaborationRepository
import org.themessagesearch.infra.db.repo.ExposedSnapshotRepository
import org.themessagesearch.infra.db.repo.ExposedAuditRepository
import org.themessagesearch.infra.db.repo.ExposedUserRepository
import org.themessagesearch.infra.db.repo.ExposedWorkflowRepository
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RepositoryIntegrationTest {
    private var postgres: PostgreSQLContainer<*>? = null
    private val docRepo = ExposedDocumentRepository()
    private val embRepo = ExposedEmbeddingRepository()
    private val collabRepo = ExposedCollaborationRepository()
    private val snapshotRepo = ExposedSnapshotRepository()
    private val auditRepo = ExposedAuditRepository()
    private val workflowRepo = ExposedWorkflowRepository()
    private val userRepo = ExposedUserRepository()
    private val actorId = UserId(UUID.randomUUID().toString())

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
        val doc = docRepo.create(sampleRequest("Migrate", "Check tables"), actorId)
        val fetched = docRepo.findById(doc.id, snapshotId = null, languageCode = doc.languageCode)
        assertNotNull(fetched)
        assertEquals(doc.title, fetched!!.title)
    }

    @Test
    fun `document CRUD and missing embedding list`() = runBlocking {
        assumeTrue(postgres != null)
        val doc = docRepo.create(sampleRequest("Title A", "Body A"), actorId)
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
            docRepo.create(sampleRequest("Title $idx", "Body $idx"), actorId).paragraphs.first()
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
            assertTrue(hasIndex("collab_updates", "idx_collab_updates_document_paragraph_id"), "Missing idx_collab_updates_document_paragraph_id")
            assertTrue(hasIndex("collab_updates", "idx_collab_updates_document_created_at"), "Missing idx_collab_updates_document_created_at")
        }
    }

    @Test
    fun `collaboration updates are idempotent`() = runBlocking {
        assumeTrue(postgres != null)
        val doc = docRepo.create(sampleRequest("Collab", "Paragraph body"), actorId)
        val paragraph = doc.paragraphs.first()
        val update = CollaborationUpdate(
            documentId = doc.id,
            paragraphId = paragraph.id,
            clientId = UUID.randomUUID().toString(),
            userId = "user-1",
            languageCode = doc.languageCode,
            seq = 1,
            payload = byteArrayOf(1, 2, 3)
        )
        val first = collabRepo.appendUpdate(update)
        val second = collabRepo.appendUpdate(update)
        assertTrue(first.accepted)
        assertFalse(second.accepted)
        assertEquals(first.latestUpdateId, second.latestUpdateId)
    }

    @Test
    fun `collaboration update pagination`() = runBlocking {
        assumeTrue(postgres != null)
        val doc = docRepo.create(sampleRequest("Collab Paginate", "First paragraph"), actorId)
        val paragraph = doc.paragraphs.first()
        val clientId = UUID.randomUUID().toString()
        val update1 = CollaborationUpdate(
            documentId = doc.id,
            paragraphId = paragraph.id,
            clientId = clientId,
            userId = "user-2",
            languageCode = doc.languageCode,
            seq = 1,
            payload = byteArrayOf(4)
        )
        val update2 = update1.copy(seq = 2, payload = byteArrayOf(5))
        val id1 = collabRepo.appendUpdate(update1).latestUpdateId
        collabRepo.appendUpdate(update2)
        val results = collabRepo.listUpdates(doc.id, paragraph.id, doc.languageCode, afterId = id1, limit = 10)
        assertEquals(1, results.size)
        assertEquals(2, results.first().seq)
    }

    @Test
    fun `collaboration snapshot roundtrip`() = runBlocking {
        assumeTrue(postgres != null)
        val doc = docRepo.create(sampleRequest("Collab Snapshot", "Body"), actorId)
        val snapshot = CollaborationSnapshot(
            documentId = doc.id,
            languageCode = doc.languageCode,
            snapshotVersion = 10,
            payload = byteArrayOf(9, 8, 7)
        )
        collabRepo.upsertSnapshot(snapshot)
        val fetched = collabRepo.getSnapshot(doc.id, doc.languageCode)
        assertNotNull(fetched)
        assertEquals(10, fetched!!.snapshotVersion)
        assertArrayEquals(byteArrayOf(9, 8, 7), fetched.payload)
    }

    @Test
    fun `dimension mismatch throws`() = runBlocking {
        assumeTrue(postgres != null)
        val doc = docRepo.create(sampleRequest("Dim", "Mismatch"), actorId)
        val paragraph = doc.paragraphs.first()
        val bad = FloatArray(10) { 0f }
        val ex = assertThrows<IllegalArgumentException> { runBlocking { embRepo.upsertParagraphEmbedding(paragraph.id, bad) } }
        assertTrue(ex.message!!.contains("Vector length"))
    }

    @Test
    fun `user roles and audits roundtrip`() = runBlocking {
        assumeTrue(postgres != null)
        val adminId = UserId(UUID.randomUUID().toString())
        userRepo.findOrCreateFromAuth(adminId, listOf(UserRole.ADMIN), email = null, displayName = null)
        val created = userRepo.createUser(
            UserCreateRequest(email = "user@example.com", displayName = "User", roles = listOf(UserRole.EDITOR)),
            adminId
        )
        assertNotNull(created)
        val fetched = userRepo.findById(created!!.id)
        assertNotNull(fetched)
        assertEquals(listOf(UserRole.EDITOR), fetched!!.roles)
        val updated = userRepo.replaceRoles(created.id, listOf(UserRole.REVIEWER), adminId, "promoted")
        assertNotNull(updated)
        assertEquals(listOf(UserRole.REVIEWER), updated!!.roles)
        val statusUpdated = userRepo.updateStatus(created.id, UserStatus.DISABLED, adminId, "left company")
        assertNotNull(statusUpdated)
        assertEquals(UserStatus.DISABLED, statusUpdated!!.status)
        val audits = userRepo.listAudits(created.id, 10, null)
        assertTrue(audits.items.any { it.action == UserAuditAction.USER_CREATED })
        assertTrue(audits.items.any { it.action == UserAuditAction.ROLES_REPLACED })
        assertTrue(audits.items.any { it.action == UserAuditAction.STATUS_CHANGED })
    }

    @Test
    fun `snapshot and audit created on publish`() = runBlocking {
        assumeTrue(postgres != null)
        val doc = docRepo.create(sampleRequest("Snapshot doc", "Snapshot body", publish = true), actorId)
        val snapshots = snapshotRepo.list(doc.id, limit = 10, cursor = null)
        assertEquals(1, snapshots.items.size)
        val snapshot = snapshots.items.first()
        assertEquals(doc.id.value, snapshot.documentId.value)
        assertEquals(1, snapshot.version)
        assertEquals(SnapshotState.PUBLISHED, snapshot.state)
        val fetched = snapshotRepo.findById(doc.id, snapshot.snapshotId)
        assertNotNull(fetched)

        val audits = auditRepo.list(doc.id, limit = 10, cursor = null)
        assertTrue(audits.items.any { it.action == DocumentAuditAction.PUBLISH })
        val audit = audits.items.first { it.action == DocumentAuditAction.PUBLISH }
        assertEquals(snapshot.snapshotId.value, audit.snapshotId?.value)
    }

    @Test
    fun `workflow review approve and archive`() = runBlocking {
        assumeTrue(postgres != null)
        val doc = docRepo.create(sampleRequest("Workflow doc", "Draft body"), actorId)
        assertEquals(DocumentWorkflowState.DRAFT, doc.workflowState)
        val review = workflowRepo.submitForReview(
            doc.id,
            expectedVersion = doc.version,
            summary = "ready for review",
            reviewers = listOf(actorId),
            actorId = actorId
        )
        assertNotNull(review)
        val approve = workflowRepo.approveReview(
            doc.id,
            review!!.reviewId,
            expectedVersion = doc.version + 1,
            reason = "looks good",
            diffSummary = "initial publish",
            actorId = actorId
        )
        assertNotNull(approve)
        assertEquals(DocumentWorkflowState.PUBLISHED, approve!!.state)
        assertNotNull(approve.snapshotId)

        val archive = workflowRepo.archive(
            doc.id,
            expectedVersion = approve.version,
            reason = "deprecated",
            actorId = actorId
        )
        assertNotNull(archive)
        assertEquals(DocumentWorkflowState.ARCHIVED, archive!!.state)
    }

    private fun sampleRequest(
        title: String,
        body: String,
        languageCode: String = "en-US",
        publish: Boolean = false
    ): DocumentCreateRequest {
        val paragraphs = body.split("\n\n").mapIndexed { idx, text ->
            DocumentParagraphInput(position = idx, heading = null, body = text, languageCode = languageCode)
        }
        return DocumentCreateRequest(title = title, languageCode = languageCode, paragraphs = paragraphs, publish = publish)
    }

    private fun randomVector(): FloatArray = FloatArray(1536) { Random.nextFloat() }
}
