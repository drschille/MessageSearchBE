# Collaboration (CRDT) Specification

## Status
Draft

## Purpose
Enable real-time collaborative editing of a `Document` across multiple authenticated clients with eventual consistency, offline edits, and conflict-free merging using a CRDT.

This spec defines backend responsibilities in **MessageSearchBE** (Ktor + Postgres/pgvector), including data storage, synchronization APIs, and operational constraints.

## Goals
- Multiple clients can edit the same document concurrently with deterministic convergence.
- Treat **paragraphs as the first sub-level** of a document so updates can be scoped narrowly while still rolling up to the parent document.
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

### CollaborationParagraph
Tracks the metadata for the first sub-level within a document (paragraph).
- `paragraphId`
- `documentId`
- `languageCode` (BCP 47, e.g., `en-US`)
- `position` (ordering within the document)
- `createdAt`
- `updatedAt`

### CollaborationUpdate
Append-only record of CRDT updates targeting a paragraph.
- `id` (monotonic)
- `documentId`
- `paragraphId`
- `clientId`
- `userId`
- `languageCode`
- `seq` (per-client sequence to support idempotency)
- `payload` (bytes)
- `createdAt`

### CollaborationSnapshot
Materialized state to bound replay cost.
- `documentId`
- `languageCode`
- `snapshotVersion` (update id watermark)
- `payload` (bytes; map of paragraph payloads or full CRDT document state)
- `createdAt`

---

## Persistence (Postgres)

### Tables (Flyway-managed)
1. `collab_paragraphs`
     - `paragraph_id UUID PRIMARY KEY`
     - `document_id UUID NOT NULL`
     - `language_code TEXT NOT NULL`
     - `position INT NOT NULL`
     - `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`
     - `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`
     - Unique constraint: `(document_id, language_code, position)` to enforce ordering

2. `collab_updates`
     - `id BIGSERIAL PRIMARY KEY`
     - `document_id UUID NOT NULL`
     - `paragraph_id UUID NOT NULL`
     - `client_id UUID NOT NULL`
     - `user_id TEXT NOT NULL`
     - `language_code TEXT NOT NULL`
     - `seq BIGINT NOT NULL`
     - `payload BYTEA NOT NULL`
     - `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`
     - Unique constraint: `(document_id, paragraph_id, client_id, seq)` for idempotent retry handling
     - Indexes:
         - `(document_id, paragraph_id, id)` for fast range queries by paragraph
         - `(document_id, created_at)` optional for TTL/cleanup jobs

3. `collab_snapshots`
     - `document_id UUID NOT NULL`
     - `language_code TEXT NOT NULL`
     - `PRIMARY KEY (document_id, language_code)`
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
         - `paragraphId: UUID`
         - `languageCode: String`
         - `seq: Long`
         - `update: String` (base64)
     - Behavior:
         - Insert into `collab_updates` (idempotent via unique constraint).
         - Return server watermark `latestUpdateId`.
     - Response:
         - `accepted: Boolean`
         - `latestUpdateId: Long`

2. `GET /documents/{documentId}/collab/updates?paragraphId={pid}&languageCode={lang}&afterId={id}&limit={n}`
     - Returns updates strictly after `afterId` ordered by `id` for the requested paragraph/language.
     - Response items include:
         - `id`, `paragraphId`, `clientId`, `seq`, `languageCode`, `update` (base64), `createdAt`
     - Notes:
         - This is a simple incremental sync for clients that track server watermark per paragraph. Supplying `paragraphId=null` replays every paragraph in the document (used for catch-up).

3. `GET /documents/{documentId}/collab/snapshot?languageCode={lang}`
     - Returns latest snapshot payload + its `snapshotVersion` for the requested language.
     - If no snapshot exists, returns `404` (client can start from empty state + updates).

### WebSocket (Preferred for Real-Time)
`GET /documents/{documentId}/collab/ws`
- On connect:
    - client sends `hello` with `clientId`, `languageCode`, the paragraph(s) it cares about, and optionally `afterId` (or library-specific state vector)
- Server:
    - streams missed updates for the requested paragraphs
    - then broadcasts incoming updates to other subscribers of the same document/language
- Message types (conceptual):
    - `hello`
    - `update` (bytes + `paragraphId`)
    - `ack` (latestUpdateId)
    - `error`

WebSocket frames should carry binary update payloads to avoid base64 overhead (JSON only for control messages).

---

## Consistency & Idempotency Rules
- Updates are applied in CRDT client; server ordering is by `collab_updates.id` per paragraph.
- Server must accept out-of-order `seq` values (per client) but must reject duplicates via `(documentId, paragraphId, clientId, seq)` constraint.
- Clients must be able to:
    - resend updates on reconnect without duplication
    - request all updates after a known watermark (or snapshot) per paragraph or language

---

## Security
- No secrets in payloads or logs.
- Enforce document-level authorization on every sync call, plus paragraph-level scoping when needed.
- Rate limits (future): per user/document/language to reduce abuse.
- Payload size limits:
    - max single update bytes (configurable; protect memory)
    - max batch size returned per request (`limit` capped)

## UI Requirements (Web/Mobile)
### Presence and Editing
- Web: show active editors list and a "live" indicator for connected status.
- Mobile: compact presence avatars and a sync status chip in the header.
- Tablet: inline presence row above the editor with expandable user list.

### Offline and Sync
- Web: explicit "offline" banner with retry controls and last sync time.
- Mobile: lightweight toast for sync failures and auto-retry indicator.
- Tablet: banner with sync diagnostics drawer.

### Conflict Handling
- Web: highlight contested ranges with inline resolution tools.
- Mobile: show conflict list with tap-to-jump and accept/merge actions.
- Tablet: split diff view with conflict list on the side.

### Snapshot Restore
- Web: allow restoring from latest collab snapshot with confirmation.
- Mobile: single action for "restore last snapshot" with undo hint.
- Tablet: restore action in a toolbar with confirmation panel.

---

## Observability
- Metrics:
    - count of updates ingested
    - bytes ingested
    - websocket connections per document
    - snapshot creation count and duration
- Logging:
    - documentId, paragraphId, userId, clientId, languageCode, updateId/seq (no payload logging)
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
- Idempotent insert behavior (same `(documentId, paragraphId, clientId, seq)` is a no-op).
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
