# Web Frontend Milestones

Milestone plan aligned with `apps/web/SPEC.md`. Each milestone targets a vertical slice that keeps the UI usable end-to-end.

Effort key: S = 1–3 days, M = 4–7 days, L = 1–2 weeks.

## W0 - Project Bootstrap (S)
- [ ] Next.js + TypeScript scaffold (App Router).
- [ ] Base layout, global styles, and design tokens.
- [ ] API client wrapper + env config (API base URL).
- [ ] Auth token handling (dev-only mock token ok).

## W1 - Search MVP (M)
- [ ] Search page with query + filters.
- [ ] Results list with paragraph snippets + citations.
- [ ] Document reader route that renders snapshot metadata.
- [ ] Error states (401/403/404/500) and empty states.

## W2 - Document List + Detail (M)
- [ ] Document list with state badge + version + updated_at.
- [ ] Filtering and sorting (state/language).
- [ ] Detail view with paragraph anchors and snapshot provenance.

## W3 - Editorial Workflow (M)
- [ ] Review queue for InReview drafts.
- [ ] Review actions: approve/request changes with reason.
- [ ] Draft editor skeleton with state badge + last updated.
- [ ] Role-gated actions wired to JWT roles.

## W4 - Snapshots + Audits (M)
- [ ] Snapshot timeline with view/revert entry points.
- [ ] Audit timeline with filters (action/actor/date).
- [ ] Redaction behavior for sensitive fields (admin reveal).

## W4.1 - Admin Tools (M)
- [ ] User list with role badges + status.
- [ ] Create user flow (admin-only).
- [ ] Role assignment editor with required reason.
- [ ] Enable/disable user status with confirmation.

## W5 - Collaboration v0 (L)
- [ ] WebSocket connection lifecycle (connect/reconnect).
- [ ] Presence indicators for active editors.
- [ ] CRDT editor integration stub (load/save update stream).
- [ ] Conflict surfacing for concurrent edits (UI-only if backend not ready).

## W6 - Hardening (M)
- [ ] Optimistic locking conflicts surfaced in UI.
- [ ] Pagination + loading states across list views.
- [ ] Telemetry hooks for key interactions.
- [ ] Basic accessibility pass (keyboard nav, focus states).

## Exit Criteria
- Search → read works end-to-end with snapshot metadata and citations.
- Editors can see review queue and complete approve/request-changes flow.
- Snapshots/audits visible and role-gated.
- Collaboration presence visible; CRDT edits can be toggled on once backend is ready.
