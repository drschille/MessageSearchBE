## 1. Goal

Build a small Kotlin backend that:
* serves structured data via REST/JSON,
* provides semantic search over documents (hybrid vector + full-text), 
* proxies/coordinates AI API calls (embeddings + optional RAG answers).  

## 2. Non-goals

* No heavy admin UI; simple OpenAPI + cURL/docs is enough.  
* No multi-tenant billing or complex ACLs (single-tenant JWT only).
 
## 3. Architecture

### Modules
* app: Ktor app (routing, DI, security, metrics)  
* core: domain models, ports, services, use cases  
* infra:db: Postgres/pgvector repositories, migrations (Flyway)  
* infra:ai: AI client (embeddings + chat/generate), provider-agnostic  
* infra:search: hybrid search service (tsvector + pgvector SQL)  
* test:e2e: Testcontainers, black-box tests  

### Data
* documents(id UUID, title TEXT, body TEXT, tsv tsvector)
* doc_embeddings(doc_id UUID PK, vec vector(1536))

### Endpoints (initial)

* POST /v1/documents – ingest doc
* GET /v1/documents/{id}
* POST /v1/search – { query: String } → hybrid results
* POST /v1/answer – RAG-style: uses search top-k + AI generate
* POST /v1/ingest/embed – backfill embeddings for existing docs (admin)

### Security

* Bearer JWT (HS256), per-route rate limits (e.g., IP + token)
* Observability
* Request/response logging (redact secrets), basic metrics
* Performance
* Vector index: ivfflat (lists = 200) to start
* Hybrid search: top-200 vector candidates → re-rank w/ text score

### Config

* application.yaml with db, ai.provider, ai.apiKey, security.jwt, search.hybridWeights

4. Migrations (Flyway)
``` sql
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

5. Hybrid search SQL (parameterized)
``` sql
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

## 6. AI providers

Start with embeddings only (cheap, stable).
Interface:
* EmbeddingClient.embed(texts: List<String>): List<FloatArray>
* ChatClient.generate(prompt: String, context: List<String>): String
Implementations: OpenAI/Azure/OpenRouter; inject via config.

## 7. Acceptance criteria

* POST /v1/documents stores doc, returns ID.
* POST /v1/ingest/embed backfills embeddings in batches (idempotent).
* POST /v1/search returns relevant docs in <150ms p95 for 50k docs (local dev, warm cache).
* POST /v1/answer returns an answer with citations to doc IDs.
* All endpoints have OpenAPI docs (/openapi + Swagger UI).
* Tests cover: repository CRUD, hybrid search, answer generation (stubbed AI).