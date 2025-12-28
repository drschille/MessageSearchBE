# Collaboration (CRDT) Specification

## Status
Draft

## Purpose
Enable real-time collaborative editing of a `Document` across multiple authenticated clients with eventual consistency, offline edits, and conflict-free merging using a CRDT.

This spec defines backend responsibilities in **MessageSearchBE** (Ktor + Postgres/pgvector), including data storage, synchronization APIs, and operational constraints.

## Goals
- Multiple clients can edit the same document concurrently with deterministic convergence.
- Support offline edits and later synchronization.
- Provide incremental sync (send/receive deltas, not full document each time).
- Preserve an auditable stream of changes (for replay/debugging) with bounded storage.
- Keep integration compatible with existing:
    - JWT auth enforcement on API routes
    - `/health` and `/metrics` endpoints
    - Postgres persistence + Flyway migrations
    - Domain `Document` concept in `core/`

## Non-Goals
- UI/editor implementation details (frontend concern).
- Full access control model beyond basic authorization checks (can be extended later).
- Rich presence features (cursors, selections) beyond a minimal event channel (optional).
- “Google Docs”-level history browsing and per-character attribution (future).

---

## CRDT Model

### Chosen Approach
**Operation-based CRDT with library-defined binary updates** (Automerge changes), treated by the backend as opaque payloads.

Backend does **not** interpret or transform operations. It:
- authenticates and authorizes,
- stores updates,
- streams updates to peers,
- provides snapshotting and compaction.

### Key Concepts
- **Document ID**: Stable identifier for a collaboratively edited document.
- **Actor/Client ID**: Unique per device/session (client-generated UUID recommended).
- **Update**: CRDT delta encoded as bytes (base64 in JSON, raw bytes on WebSocket).
- **Vector clock / state vector**: Client-provided “known state” for requesting missing updates (library-specific; opaque to server, but used for indexing if available).

---

## Domain Model (Conceptual)

### CollaborationSession
Represents a connected client participating in a document.
- `documentId`
- `clientId`
- `userId` (from JWT)
- `connectedAt`
- `lastSeenAt`

### CollaborationUpdate
Append-only record of CRDT updates.
- `id` (monotonic)
- `documentId`
- `clientId`
- `userId`
- `seq` (per-client sequence to support idempotency)
- `payload` (bytes)
- `createdAt`

### CollaborationSnapshot
Materialized state to bound replay cost.
- `documentId`
- `snapshotVersion` (update id watermark)
- `payload` (bytes; full CRDT document state)
- `createdAt`

---

## Persistence (Postgres)

### Tables (Flyway-managed)
1. `collab_updates`
     - `id BIGSERIAL PRIMARY KEY`
     - `document_id UUID NOT NULL`
     - `client_id UUID NOT NULL`
     - `user_id TEXT NOT NULL`
     - `seq BIGINT NOT NULL`
     - `payload BYTEA NOT NULL`
     - `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`
     - Unique constraint: `(document_id, client_id, seq)` for idempotent retry handling
     - Indexes:
         - `(document_id, id)` for fast range queries
         - `(document_id, created_at)` optional for TTL/cleanup jobs

2. `collab_snapshots`
     - `document_id UUID PRIMARY KEY`
     - `snapshot_version BIGINT NOT NULL` (max `collab_updates.id` included)
     - `payload BYTEA NOT NULL`
     - `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`

### Retention & Compaction
- Updates are append-only until compacted.
- Snapshot policy (initial):
    - Create/replace snapshot when either threshold is reached:
        - `N` updates since last snapshot (configurable; e.g., 500)
        - or total update bytes exceeds threshold (configurable)
- After snapshotting:
    - Keep updates **newer than** `snapshot_version` only (optional; configurable).
    - Alternatively, keep a rolling window for audit/debug (e.g., last 7 days).

---

## API Surface (Ktor)

All endpoints require JWT authentication. Authorization must ensure the user is allowed to read/write the `documentId` (exact rule depends on existing document ownership/sharing rules; if absent, default to “creator-only” or “internal-only” until defined).

### REST Endpoints (Baseline)
1. `POST /documents/{documentId}/collab/updates`
     - Request:
         - `clientId: UUID`
         - `seq: Long`
         - `update: String` (base64)
     - Behavior:
         - Insert into `collab_updates` (idempotent via unique constraint).
         - Return server watermark `latestUpdateId`.
     - Response:
         - `accepted: Boolean`
         - `latestUpdateId: Long`

2. `GET /documents/{documentId}/collab/updates?afterId={id}&limit={n}`
     - Returns updates strictly after `afterId` ordered by `id`.
     - Response items include:
         - `id`, `clientId`, `seq`, `update` (base64), `createdAt`
     - Notes:
         - This is a simple incremental sync for clients that track server watermark.

3. `GET /documents/{documentId}/collab/snapshot`
     - Returns latest snapshot payload + its `snapshotVersion`.
     - If no snapshot exists, returns `404` (client can start from empty state + updates).

### WebSocket (Preferred for Real-Time)
`GET /documents/{documentId}/collab/ws`
- On connect:
    - client sends `hello` with `clientId` and optionally `afterId` (or library-specific state vector)
- Server:
    - streams missed updates
    - then broadcasts incoming updates to other subscribers of the same document
- Message types (conceptual):
    - `hello`
    - `update` (bytes)
    - `ack` (latestUpdateId)
    - `error`

WebSocket frames should carry binary update payloads to avoid base64 overhead (JSON only for control messages).

---

## Consistency & Idempotency Rules
- Updates are applied in CRDT client; server ordering is by `collab_updates.id`.
- Server must accept out-of-order `seq` values (per client) but must reject duplicates via `(documentId, clientId, seq)` constraint.
- Clients must be able to:
    - resend updates on reconnect without duplication
    - request all updates after a known watermark (or snapshot)

---

## Security
- No secrets in payloads or logs.
- Enforce document-level authorization on every sync call.
- Rate limits (future): per user/document to reduce abuse.
- Payload size limits:
    - max single update bytes (configurable; protect memory)
    - max batch size returned per request (`limit` capped)

---

## Observability
- Metrics:
    - count of updates ingested
    - bytes ingested
    - websocket connections per document
    - snapshot creation count and duration
- Logging:
    - documentId, userId, clientId, updateId/seq (no payload logging)
- `/health` and `/metrics` must remain unaffected.

---

## Integration With Search / Indexing (Optional, Phase 2)
If `Document` content is used for hybrid search:
- Define an asynchronous job that materializes the latest CRDT state into plain text.
- Trigger re-indexing:
    - on snapshot creation, or
    - on a debounce window after last update (e.g., 2–5 seconds of inactivity).
- Store derived plain text separately from CRDT storage to avoid coupling.

---

## Testing Plan
### Unit Tests
- Idempotent insert behavior (same `(documentId, clientId, seq)` is a no-op).
- Pagination/range correctness for `GET updates`.
- Authorization checks are enforced.

### Integration Tests (Testcontainers in `infra/db`)
- Flyway migration creates tables and indexes.
- Insert + fetch roundtrip with realistic payload sizes.
- Snapshot upsert behavior and compaction rules.

### E2E Tests (in `test/e2e`)
- Simulate two clients editing:
    - Client A posts updates
    - Client B receives via polling or WebSocket
    - Both converge (client-side CRDT library responsibility; backend verifies delivery/order guarantees)

---

## Open Questions
- Confirm Automerge as the standard CRDT library for clients and lock the expected wire formats (state vector support and message encoding).
- Exact authorization model for shared documents (owners, teams, link-sharing).
- Snapshot thresholds and retention defaults.
- Whether server should support state-vector-based sync (more efficient than `afterId`) once client library is chosen.

---