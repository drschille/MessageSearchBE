# Progress Checklist

This document tracks implementation progress against `README.md` and the specs in `specs/`.
Status is a quick snapshot and may lag behind active work.

## Read & Search
- [x] Document ingest/list/get endpoints (basic)
- [x] Paragraph storage and paragraph embedding backfill
- [x] Hybrid search endpoint and scoring (paragraph level)
- [x] Answer endpoint with stubbed AI
- [ ] Document-level embeddings storage + backfill
- [ ] Snapshot-aware search + answer citations (Published only)
- [ ] ETag/If-Match + 409 conflict handling on writes
- [ ] Idempotency-Key support for mutating endpoints
- [ ] Rate limiting per token/IP
- [ ] Full OpenAPI coverage for all endpoints
- [ ] Real AI providers (embeddings + chat) configurable by `application.yaml`

## Editorial Workflow
- [ ] Workflow state model + persistence (Draft/InReview/Published/Archived)
- [ ] Transition endpoints (submit, approve, request changes, publish, archive, revert)
- [ ] Optimistic locking for transitions
- [ ] Review notes/comments storage and retention
- [ ] Notifications/webhooks for review/publish events

## Snapshot & Audit
- [ ] Snapshot persistence (schema + repository)
- [ ] Audit events (schema + repository)
- [ ] Snapshot list/fetch endpoints
- [ ] Document audit list/fetch endpoints
- [ ] Search/answer references `snapshot_id` and version

## Collaboration (CRDT)
- [x] Update ingestion + listing endpoints
- [x] Snapshot storage for collab state
- [ ] WebSocket realtime sync
- [ ] Collab paragraph metadata management
- [ ] Snapshot/compaction policy + retention
- [ ] State-vector-based incremental sync

## Users & Roles
- [x] JWT role enforcement (basic)
- [x] User CRUD + role changes + status updates
- [x] User audit events and pagination
- [ ] Verify JWT `iss/aud` handling across environments

## Cross-cutting
- [x] Metrics/log redaction for sensitive fields
- [ ] End-to-end tests for workflow/snapshot/audit flows
