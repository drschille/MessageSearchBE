# Milestones

This file groups the spec backlog into delivery milestones. Each milestone assumes backend-only scope.

Effort key: S = 0.5–2 days, M = 3–5 days, L = 6–10 days, XL = 2+ weeks.

## M0 - Search MVP
- [x] Document ingest/list/get (S, already done)
- [x] Paragraph embeddings backfill (S, already done)
- [x] Hybrid search endpoint (S, already done)
- [x] Answer endpoint with stub AI (S, already done)
- [x] Metrics/log redaction for prompts and sensitive fields (S–M)

## M1 - Snapshot Backbone
- [ ] Snapshot schema + repository (L)
- [ ] Audit events schema + repository (L)
- [ ] Snapshot list/fetch endpoints (M)
- [ ] Document audit list/fetch endpoints (M)
- [ ] Search and answer cite `snapshot_id` and version (M)

## M2 - Editorial Workflow
- [ ] Workflow state model + persistence (L)
- [ ] Draft/review/publish/archive transition endpoints (L)
- [ ] Optimistic locking for transitions (M)
- [ ] Review notes/comments storage (M)
- [ ] Publish/review notifications (webhook first) (M–L)

## M3 - Collaboration Realtime
- [ ] WebSocket realtime sync for collab updates (L)
- [ ] Collab paragraph metadata management (S–M)
- [ ] Snapshot/compaction policy + retention (M)
- [ ] State-vector-based incremental sync (M)

## M4 - Hardening
- [ ] Idempotency-Key support for mutating endpoints (M)
- [ ] ETag/If-Match handling on writes (M)
- [ ] Rate limiting per token/IP (M)
- [ ] Replace AI stubs with provider-backed clients (M)
- [ ] OpenAPI coverage for current endpoints (S–M)
- [ ] End-to-end tests for workflow/snapshot/audit flows (L)
