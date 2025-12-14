## Purpose
Describe how read operations and semantic search behave end to end. This spec covers query flows, ranking mechanics, storage requirements, and operational safeguards for serving trustworthy answers.

## Principles
- **Editorially defensible:** every result must reference immutable document snapshots; no unseen rewriting.
- **Attributable:** responses cite `DocumentId` values so readers can verify sources.
- **Observable:** search latency, hit counts, and AI call volume are exported via Micrometer/Prometheus.

## Scope & Non-Goals
In scope: REST reads (`/v1/documents`, `/v1/search`, `/v1/answer`), embedding backfill, and SQL used by `HybridSearchService`. Out of scope: authoring workflow (see `editorial-workflow.md`), multi-tenant ACLs, and UI tooling.

## Module Responsibilities
- `core`: defines `Document`, `HybridWeights`, and ports `DocumentRepository`, `HybridSearchService`, `AnswerService`, `EmbeddingBackfillService`.
- `infra:db`: Postgres/pgvector schema, CRUD, and list-IDs-missing-embeddings queries.
- `infra:search`: orchestrates hybrid scoring, vector reranking, and backfill batching.
- `infra:ai`: wraps provider-specific embeddings/chat APIs; default stubs unblock local runs.
- `app`: exposes endpoints, validates payloads, and wires services (see `ServiceRegistry`).

## Data Model
```text
documents(id UUID PK, title text, body text,
          tsv tsvector generated from title/body, created_at timestamptz)
doc_embeddings(doc_id UUID PK FK documents, vec vector(1536), updated_at timestamptz)
```
- `idx_documents_tsv`: `GIN(tsv)` for lexical matches.
- `idx_embeddings_vec`: `IVFFLAT(vec vector_cosine_ops)` with `lists=200`; requires `ANALYZE` after bulk loads.
- Derived weights: `HybridWeights(text=0.35, vector=0.65)` configurable in `application.yaml`.

## Query Flow
1. **Doc fetch**: `/v1/documents/{id}` performs repository lookup; only returns 404 or 200 with exact persisted fields.
2. **Embed backfill**: `/v1/ingest/embed` selects up to `batchSize` IDs lacking embeddings, requests vectors from `EmbeddingClient`, and upserts via `EmbeddingsRepository.batchUpsertEmbeddings` (all-or-nothing).
3. **Hybrid search**: `HybridSearchService.search(query, limit, weights)` computes embeddings (via AI client), executes SQL below, and returns ordered `SearchHit`s with both scores.
4. **Answering**: `AnswerService.answer(query, k, weights)` reuses search top-k, builds a prompt containing titles/snippets, and calls `ChatClient.generate`. Response must include `AnswerResponse.citations` referencing source IDs.

### Hybrid SQL (parameterized)
```sql
WITH q AS (
  SELECT to_tsvector('simple', :query) AS qts,
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
       coalesce(th.text_score, 0) AS text_score,
       coalesce(vh.vec_score, 0)  AS vec_score,
       (:w_text * coalesce(th.text_score, 0) + :w_vec * coalesce(vh.vec_score, 0)) AS final_score
FROM documents d
LEFT JOIN text_hits th ON th.id = d.id
LEFT JOIN vec_hits vh ON vh.id = d.id
WHERE coalesce(th.text_score, 0) > 0 OR coalesce(vh.vec_score, 0) > 0
ORDER BY final_score DESC
LIMIT :limit;
```

## Security & Observability
- All routes except `/health`, `/metrics`, `/openapi` require JWT (`auth-jwt`). Tokens must include configured issuer/audience; clock skew tolerance ±60s.
- Metrics captured: request latency per endpoint, search hit counts, AI call duration, errors logged via `StatusPages` with redacted payloads.
- PII or secrets must never enter logs; redact query text in error paths.

## Performance Targets
- `/v1/search`: p95 < 150 ms for 50k docs with warm pgvector cache; fail fast if embeddings API exceeds 2 s.
- `/v1/answer`: p95 < 3 s assuming upstream model latency < 2.5 s; degrade gracefully by returning search hits + “answer unavailable”.
- Backfill batches default to 50 IDs to balance rate limits and DB contention; configurable via request body.

## Testing & Validation
- Unit: `HybridSearchServiceTest` (add fixtures), AI stubs verifying prompt composition, repository CRUD.
- Integration: `RepositoryIntegrationTest` ensures migrations, vector dimension checks, and index presence.
- Contract: E2E tests should seed sample docs, run `/v1/search` and `/v1/answer`, and verify deterministic ranking when embeddings are stubbed.

## Change Management
- Schema changes must be introduced via Flyway migrations under `infra/db/src/main/resources/db/migration`.
- Update `search.weights` defaults when tuning ranking; document rationale in commit message and note dashboards reviewed.
- Coordinate AI provider switches with ops: rotate API keys via env vars (`AI_API_KEY`) and validate rate limits.
