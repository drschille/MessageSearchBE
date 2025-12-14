# Read & Search Specification

Defines how documents are stored, indexed, and retrieved for readers and AI-assisted workflows.

## 1. Goals
- Serve structured document data via REST/JSON with consistent schemas and OpenAPI documentation.
- Provide hybrid semantic search (vector + full-text) with predictable ranking and performance.
- Enable AI-powered answering (RAG) that cites source documents and can run against offline stubs for testing.

## 2. Non-goals
- Rich admin UI or editorial tooling (covered in the editorial workflow spec).
- Multi-tenant billing or complex ACLs; single-tenant JWT roles are sufficient.
- Automatic content moderation; rely on upstream providers or future work.

## 3. Architecture
### Modules
- **app:** Ktor routes for document ingest, search, answer, and embedding backfill; installs JWT auth and metrics.
- **core:** Domain models (`Document`, `Snapshot`, `HybridWeights`), ports for repositories, search, and AI clients.
- **infra:db:** Postgres/pgvector repositories using Exposed, plus Flyway migrations.
- **infra:ai:** Embedding and chat clients with provider-agnostic interfaces and stubs for tests.
- **infra:search:** Hybrid search orchestrator that combines vector and text scores.
- **test:e2e:** Black-box tests using Testcontainers.

### Data Model
- `documents(id UUID, title TEXT, body TEXT, tsv tsvector)`
- `doc_embeddings(doc_id UUID PK, vec vector(1536))`
- Optional: `snapshots(id UUID, document_id UUID, title TEXT, body TEXT, created_at TIMESTAMPTZ)` for immutable published views.

### Endpoints (minimum viable set)
- `POST /v1/documents` – ingest a document; returns ID and version.
- `GET /v1/documents/{id}` – fetch by ID (latest or specific snapshot via query).
- `POST /v1/search` – body `{ query: String, limit?: Int, weights?: { text: Double, vector: Double } }`.
- `POST /v1/answer` – body `{ query: String, limit?: Int }`; uses search then chat/generation.
- `POST /v1/ingest/embed` – backfill embeddings for existing docs (admin only) with pagination and idempotency.

JWT (HS256) is required for all endpoints. Rate limiting is enforced per token and IP.

## 4. Migrations (Flyway)
```sql
-- V1__core.sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE documents(
   id UUID PRIMARY KEY,
   title TEXT NOT NULL,
   body TEXT NOT NULL,
   tsv tsvector GENERATED ALWAYS AS (
      setweight(to_tsvector('simple', coalesce(title,'')), 'A') ||
      setweight(to_tsvector('simple', coalesce(body, '')), 'B')
   ) STORED
);
CREATE TABLE doc_embeddings(
   doc_id UUID PRIMARY KEY REFERENCES documents(id) ON DELETE CASCADE,
   vec   vector(1536) NOT NULL
);
CREATE INDEX idx_documents_tsv ON documents USING gin(tsv);
CREATE INDEX idx_embeddings_vec ON doc_embeddings USING ivfflat (vec vector_cosine_ops)
   WITH (lists = 200);
```

## 5. Hybrid Search SQL (reference)
```sql
WITH q AS (
   SELECT
      to_tsvector('simple', :query) AS qts,
      :embed::vector(1536)          AS qvec
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
   LIMIT :k
)
SELECT d.id, d.title,
      coalesce(th.text_score,0) AS text_score,
      coalesce(vh.vec_score,0)  AS vec_score,
      (:w_text*coalesce(th.text_score,0) + :w_vec*coalesce(vh.vec_score,0)) AS final_score
FROM documents d
LEFT JOIN text_hits th ON th.id = d.id
LEFT JOIN vec_hits vh ON vh.id = d.id
WHERE coalesce(th.text_score,0) > 0 OR coalesce(vh.vec_score,0) > 0
ORDER BY final_score DESC
LIMIT :limit;
```

## 6. AI Providers
- Start with embeddings; chat/generation optional for RAG answering.
- Interfaces:
  - `EmbeddingClient.embed(texts: List<String>): List<FloatArray>`
  - `ChatClient.generate(prompt: String, context: List<String>): String`
- Implementations: OpenAI/Azure/OpenRouter; injectable via configuration. Provide deterministic stub for tests.

## 7. Performance & Observability
- Target: `POST /v1/search` p95 < 150ms for 50k documents on warm cache; log vector index settings for transparency.
- Use ivfflat index with `lists = 200` as default; expose tuning via configuration.
- Emit metrics: request latency, result count, embedding latency, and RAG token counts. Redact prompts before logging.

## 8. Acceptance Criteria
- Documents can be ingested and fetched by ID (latest or specific snapshot).
- Embedding backfill is idempotent and can resume via cursor/batch parameters.
- Hybrid search returns relevant documents with stable ranking and includes raw scores.
- Answer endpoint returns text plus citations of document IDs/snapshots used.
- OpenAPI docs available at `/openapi`; Swagger UI enabled for local testing.
- Tests cover repository CRUD, hybrid search, and answer generation (with stubbed AI).
