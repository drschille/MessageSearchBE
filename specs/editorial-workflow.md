# Editorial Workflow Specification

Defines the lifecycle of a document from creation to archival, including roles, permissions, and audit expectations. This spec applies to all authoring and publishing surfaces.

## Roles
- **Reader:** Can view published snapshots only.
- **Editor:** Can create drafts, edit drafts, propose reviews, and view audit history of their own drafts.
- **Reviewer:** Everything an editor can do plus approve/reject drafts, and request changes.
- **Admin:** All reviewer privileges plus force-publish, archive, and manage role assignments.

## Document Lifecycle
Documents advance through a state machine. Every transition records `actor_id`, `timestamp`, `from_state`, `to_state`, and a free-form `reason`.

```text
Draft -> InReview -> Published -> Archived
  ^         |           |            |
  |         |           |            |__ RequestUnarchive
  |         |           |__ Deprecate
  |         |__ RequestChanges
  |__ Revert (creates new Draft from prior snapshot)
```

### States
- **Draft:** Editable working copy owned by one or more editors. Versioned via CRDT operations and saved as checkpoints (see Collaboration spec).
- **InReview:** Read-only to editors except for inline comments. Reviewers can approve or request changes.
- **Published:** Immutable snapshot visible to readers and search. Subsequent edits create a new Draft derived from the published snapshot.
- **Archived:** Hidden from search and read APIs. Can be unarchived to Published with admin approval.

### Transitions
- **CreateDraft:** Editor creates a new document or forks from Published; sets initial owner list.
- **SubmitForReview:** Editor moves Draft → InReview; must provide summary of changes and reviewers.
- **RequestChanges:** Reviewer flags issues; system creates a new Draft based on the existing InReview content and links to prior review notes.
- **Approve:** Reviewer moves InReview → Published; publishes a snapshot and writes an audit event.
- **Deprecate:** Admin marks Published → Archived; does not delete data.
- **RequestUnarchive:** Editor or reviewer proposes unarchiving; Admin decides.
- **ForcePublish:** Admin can publish directly from Draft (emergency only); requires reason string and is highly auditable.
- **Revert:** Editor or admin creates a new Draft from any prior snapshot; does not mutate historical snapshots.

All transitions must fail if the caller lacks the required role, if the document changed since the caller fetched it (optimistic locking), or if mandatory fields are missing.

## Audit & Attribution
- Every transition writes an audit record containing actor_id, timestamp, IP/fingerprint, reason, and the diff summary between previous and new snapshot IDs.
- Inline comments and review notes are preserved even after publication for traceability.
- Published snapshots are addressable by ID and timestamp; clients should use these IDs in citations.

## Collaboration & Conflict Handling
- Edits occur via CRDT operations synced through the Collaboration module; server validates operations against access rules.
- Conflicts are surfaced rather than auto-resolved; the API returns a list of contested ranges for the editor to address.
- Checkpoints are stored every N operations (configurable, default 50) to enable fast recovery and review diffs.

## Notifications & SLAs
- On SubmitForReview, notify assigned reviewers (webhook + email where configured).
- If a review is idle for >48h, escalate to admins via webhook and mark the request as `stale`.
- Publish events emit webhooks containing document ID, snapshot ID, and changelog summary for downstream consumers.

## API Expectations (minimum set)
- `POST /v1/documents` – create Draft (requires Editor+)
- `POST /v1/documents/{id}/review` – submit Draft for review (Editor+)
- `POST /v1/documents/{id}/reviews/{reviewId}/approve` – approve (Reviewer+)
- `POST /v1/documents/{id}/reviews/{reviewId}/request-changes` – request changes (Reviewer+)
- `POST /v1/documents/{id}/publish` – publish or force-publish (Reviewer+ or Admin with `force=true`)
- `POST /v1/documents/{id}/revert` – create new Draft from a snapshot (Editor+)
- `POST /v1/documents/{id}/archive` – deprecate (Admin)

Each endpoint must validate JWT roles, enforce optimistic locking via `If-Match`/ETag or version numbers, and return audit IDs for logging.
