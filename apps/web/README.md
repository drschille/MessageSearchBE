# Web App

Frontend product surface for MessageSearch. This app provides document search, editorial workflow, collaboration entry points, and admin management over the APIs defined in `specs/`.

## Status
- Planned: Next.js + TypeScript (App Router)
- Not yet implemented; use this README as the functional spec for the UI.

## Product Scope
- Search and read published snapshots with paragraph-level results and citations.
- Editorial workflow for Draft/InReview/Published/Archived lifecycle.
- Snapshot and audit visibility with diff-aware views.
- User/role management for admins.
- Collaboration entry points for real-time editing (CRDT), starting with basic sync.

## Primary Screens (Web)
### Search
- Query bar with language filter and result count.
- Results show paragraph snippets, document title, and scores.
- Click-through to document reader with citations.

### Document List
- Searchable, filterable list with language and state filters.
- Sort by updated timestamp; show state badge + version.
- Quick actions: open, review, archive/revert (role-gated).

### Document Detail (Reader)
- Snapshot-aware view: title, version, state, language.
- Paragraph list with anchor links for citations.
- Show provenance: snapshot_id and published timestamp.

### Draft Editor (Editor/Reviewer/Admin)
- State badge with version and last updated.
- Review submission flow with summary + reviewer picker.
- Inline review comments (side panel) with timestamps.

### Review Queue
- List of drafts InReview with reviewer assignments.
- Approve / request changes with required reason.
- Stale review warning after 48h.

### Snapshot History
- Timeline of snapshots with version + state.
- Actions: view, revert (role-gated), compare.

### Audit Timeline
- Filterable list (action, actor, date range).
- Redaction by default; reveal requires admin.

### Users & Roles (Admin)
- User list with role badges and status.
- Role edit flow with reason prompt.
- Status toggle with confirmation.

## API Dependencies
- Search + Answer: `POST /v1/search`, `POST /v1/answer`
- Documents: `POST /v1/documents`, `GET /v1/documents/{id}`, review/publish/archive/revert endpoints
- Snapshots + Audits: `GET /v1/documents/{id}/snapshots`, `GET /v1/documents/{id}/audits`
- Users: `/v1/users` endpoints
- Collaboration: `/documents/{id}/collab/*` (REST + WebSocket when available)

## UX/Behavior Requirements
- JWT auth required; render role-gated actions based on roles claim.
- Use optimistic locking on workflow transitions; surface conflicts with clear messaging.
- For published reads, always include snapshot_id + version in the UI.
- Highlight paragraph-level search hits and keep citations visible.
- Show webhook-driven status changes (e.g., publish) as real-time notifications.
- Editing uses CRDTs and supports simultaneous, multi-editor collaboration over WebSocket.
- Collaboration UI must handle concurrent edits gracefully (presence optional, conflicts surfaced).
- Show presence indicators for other active editors in the document.

## Non-Goals (Web)
- Multi-tenant billing or tenant admin surfaces.
- Full text editor with rich presence indicators (future).
- Content moderation UI.

## References
- `specs/read-and-search.md`
- `specs/editorial-workflow.md`
- `specs/snapshot-and-audit.md`
- `specs/users-and-roles.md`
- `specs/collaboration.md`
