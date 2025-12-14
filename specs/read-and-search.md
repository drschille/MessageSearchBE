# Read & Search Specification

## Purpose
Describe how read operations and semantic search behave end to end. This spec covers query flows, ranking mechanics, storage requirements, and operational safeguards for serving trustworthy answers.

Define how documents are stored, indexed, and retrieved for readers and AI-assisted workflows. Assume JSON over HTTP with OpenAPI documentation.

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
```text
documents(
  id UUID PRIMARY KEY,
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  version BIGINT NOT NULL DEFAULT 1,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  tsv tsvector GENERATED ALWAYS AS (
    setweight(to_tsvector('simple', coalesce(title,'')), 'A') ||
    setweight(to_tsvector('simple', coalesce(body,'')), 'B')
  ) STORED
)
doc_embeddings(doc_id UUID PRIMARY KEY REFERENCES documents(id) ON DELETE CASCADE,
               vec vector(1536) NOT NULL,
               updated_at TIMESTAMPTZ NOT NULL DEFAULT now())
snapshots(
  id UUID PRIMARY KEY,
  document_id UUID NOT NULL REFERENCES documents(id),
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
)
```
Snapshots capture immutable published versions; `documents` rows may change but must bump `version` and update timestamps.

### Endpoints (minimum viable set)
- `POST /v1/documents` – ingest a document; returns `id`, `version`, and `snapshot_id` when published.
- `GET /v1/documents/{id}` – fetch by ID (latest or specific snapshot via query); supports `fields` filter and `If-None-Match` for caching.
- `POST /v1/search` – body `{ query: String, limit?: Int = 10, offset?: Int = 0, weights?: { text: Double, vector: Double } }`; returns total count, stable ordering, and raw scores.
- `POST /v1/answer` – body `{ query: String, limit?: Int = 5 }`; uses search then chat/generation, returns citations of `document_id` + `snapshot_id` per supporting passage.
- `POST /v1/ingest/embed` – backfill embeddings for existing docs (admin only) with pagination and idempotency via `cursor` and `batch_size`.

JWT (HS256) is required for all endpoints except `/health`, `/metrics`, and `/openapi`. Rate limiting is enforced per token and IP. All mutating endpoints are idempotent under a client-supplied `Idempotency-Key` header.

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
),
combined AS (
  SELECT d.id, d.title,
         coalesce(th.text_score, 0) AS text_score,
         coalesce(vh.vec_score, 0)  AS vec_score,
         (:w_text * coalesce(th.text_score, 0) + :w_vec * coalesce(vh.vec_score, 0)) AS final_score
  FROM documents d
  LEFT JOIN text_hits th ON th.id = d.id
  LEFT JOIN vec_hits vh ON vh.id = d.id
  WHERE coalesce(th.text_score, 0) > 0 OR coalesce(vh.vec_score, 0) > 0
)
SELECT json_build_object(
  'total', (SELECT count(*) FROM combined),
  'results', (
     SELECT json_agg(row_to_json(c.*))
     FROM (
       SELECT * FROM combined ORDER BY final_score DESC LIMIT :limit OFFSET :offset
     ) AS c
  )
);
```
The service unwraps `total` and `results`, ensuring API responses carry overall hit counts alongside paginated data.

## 6. API Schemas & Error Handling
- **Document representation:** `{ id: UUID, title: String, body: String, snapshot_id?: UUID, version: Long, created_at: Instant, updated_at: Instant }`. Published snapshots always include `snapshot_id` and are immutable.
- **Search response:** `{ total: Long, limit: Int, offset: Int, results: [ { id, snapshot_id?, title, snippet, text_score, vec_score, final_score } ] }`. `total` reflects the count from the `combined` CTE; `snippet` contains highlighted fragments with matched terms.
- **Answer response:** `{ answer: String, citations: [ { document_id: UUID, snapshot_id?: UUID, score: Double, excerpt: String } ], tokens_used?: { prompt: Int, completion: Int } }`.
- **Validation & errors:** 400 for malformed queries or missing weights, 401/403 for auth failures, 404 for missing documents, 409 when ETag/version preconditions fail, 429 for rate limits, 500 with correlation ID for unexpected errors.
- **Pagination:** limit defaults to 10 and caps at 100; offset-based pagination is sufficient for this phase. Expose `next_offset` helper in responses for UI convenience.

## 7. AI Providers
- Start with embeddings; chat/generation optional for RAG answering.
- Interfaces:
  - `EmbeddingClient.embed(texts: List<String>): List<FloatArray>`
  - `ChatClient.generate(prompt: String, context: List<String>): String`
- Implementations: OpenAI/Azure/OpenRouter; injectable via configuration. Provide deterministic stub for tests.

## 8. Performance & Observability
- Target: `POST /v1/search` p95 < 150ms for 50k documents on warm cache; log vector index settings for transparency.
- Use ivfflat index with `lists = 200` as default; expose tuning via configuration.
- Emit metrics: request latency, result count, embedding latency, and RAG token counts. Redact prompts before logging.

## 9. Acceptance Criteria
- Documents can be ingested and fetched by ID (latest or specific snapshot).
- Embedding backfill is idempotent and can resume via cursor/batch parameters.
- Hybrid search returns relevant documents with stable ranking and includes raw scores.
- Answer endpoint returns text plus citations of document IDs/snapshots used.
- OpenAPI docs available at `/openapi`; Swagger UI enabled for local testing.
- Tests cover repository CRUD, hybrid search, and answer generation (with stubbed AI).
