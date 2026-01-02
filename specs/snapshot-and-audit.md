# Snapshot and Audit Specification

Defines immutable snapshots and auditable events for every document mutation. This spec applies to publishing, workflow transitions, search indexing, and compliance reporting.

## Core Concepts
- **Snapshot:** An immutable, point-in-time representation of a document and its metadata.
- **Audit event:** An append-only record describing who changed what, when, and why.
- **Derived draft:** A new Draft created from a prior snapshot; never mutate the snapshot itself.

## Snapshot Model
Snapshots capture the full published view required for read and search surfaces.

Required fields:
- `snapshot_id` (UUID)
- `document_id` (UUID)
- `version` (monotonic integer per document)
- `state` (Published or Archived)
- `content` (render-ready payload, format aligned to Collaboration spec)
- `metadata` (title, tags, language, visibility, etc.)
- `created_at` (UTC timestamp)
- `created_by` (actor_id)
- `source_draft_id` (UUID, optional)
- `source_revision` (CRDT revision or checksum)

Rules:
- Snapshots are immutable and append-only.
- Creating a new snapshot increments `version` and never overwrites prior snapshots.
- Archiving creates a new snapshot with `state = Archived`; do not delete previous versions.
- Snapshots referenced by search/answering must be addressable by ID and timestamp.

## Audit Events
Every mutation emits an audit event.

Required fields:
- `audit_id` (UUID)
- `document_id` (UUID)
- `actor_id` (UUID)
- `action` (string enum)
- `reason` (string, required for publish, archive, force actions)
- `from_state` / `to_state`
- `snapshot_id` (UUID, optional, present when snapshot created)
- `diff_summary` (string or structured diff reference)
- `request_id` (string)
- `ip_fingerprint` (string, redacted/hashed)
- `created_at` (UTC timestamp)

Actions (minimum set):
- `draft.created`
- `draft.updated`
- `review.submitted`
- `review.approved`
- `review.rejected`
- `publish`
- `archive`
- `unarchive`
- `revert`
- `force_publish`

Rules:
- Audit events are append-only and never edited.
- For publish, include a `diff_summary` from prior snapshot to new snapshot.
- Link audit events to workflow transitions (see Editorial Workflow spec).

## API Expectations (minimum set)
- `GET /v1/documents/{id}/snapshots?limit&cursor` – list snapshots (default limit 50).
- `GET /v1/documents/{id}/snapshots/{snapshotId}` – fetch a snapshot.
- `GET /v1/documents/{id}/audits?limit&cursor` – list audit events (default limit 50).
- `GET /v1/documents/{id}/audits/{auditId}` – fetch a single audit event.
- `POST /v1/documents/{id}/revert` – create a new Draft from a snapshot (returns new draft ID and audit ID).

Pagination must be cursor-based and stable across concurrent writes.

## Access Control
- Readers can view snapshots for Published documents only.
- Editors and reviewers can view snapshots and audit events for documents they can access.
- Admins can view all audit events, including force actions.
- All audit reads must redact sensitive content (PII in `reason`, IP, or payloads).

## Search and Indexing
- Search indexes only Published snapshots.
- Indexing uses `snapshot_id` as the primary identifier for search results.
- RAG/answering must cite `snapshot_id` and `version` in responses.

## Observability
- Emit metrics for snapshot creation latency and audit write failures.
- Log `request_id`, `document_id`, `snapshot_id`, and `audit_id` for tracing.

## Data Retention
- Snapshots are retained indefinitely by default.
- Audit events are retained indefinitely; redaction is handled via encryption or field-level masking, not deletion.

## UI Requirements (Web/Mobile)
### Snapshot List
- Web: timeline list with version, state, created_at, and action buttons (view/revert).
- Mobile: stacked timeline cards with state pill and tap-to-open snapshot detail.
- Tablet: split list/detail with persistent timeline on the left.

### Snapshot Detail
- Web: diff-aware view with metadata panel (version, created_by, created_at).
- Mobile: single-column view with metadata drawer and scroll-to-section anchors.
- Tablet: side panel for metadata and diff controls.

### Audit Timeline
- Web: filterable timeline (action, actor, date range) with diff summary preview.
- Mobile: chronological feed with expandable audit entries and reason text.
- Tablet: filter chips row with two-column timeline layout.

### Redaction Handling
- Web/Mobile: redact reason/IP fields in UI by default; reveal requires admin role.
- Tablet: same as web, plus inline reveal for privileged users.
