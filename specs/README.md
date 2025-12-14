# System Specifications

This directory collects the product and architectural specifications for MessageSearch. Each document is meant to be concise enough for engineers to act on, while remaining stable references for reviewers and stakeholders.

## Purpose of This Directory
- Capture the foundational principles that guide feature design and tradeoffs.
- Describe the architecture, modules, and cross-cutting behaviors that all services must follow.
- Provide authoritative workflows for collaboration, editing, and search so multiple teams can work independently without diverging expectations.

## System Principles
- **Editorial decisions are explicit.** Every change to content or metadata must be the result of a deliberate user action, not an implicit side effect.
- **All actions are attributable.** Persist actor identity, timestamp, and rationale for every mutation to enable audits and rollback decisions.
- **No automatic semantic conflict resolution.** Merge strategies are opt-in and visible to editors; defaults prefer presenting conflicts over silently overwriting changes.
- **Snapshots are immutable.** Published views are read-only; new snapshots are created for updates instead of modifying existing records.

## High-Level Architecture
The platform is organized into a small set of Kotlin modules backed by PostgreSQL/pgvector and a provider-agnostic AI layer.

- **Collaboration (CRDT).** Local-first editing with mergeable operations that preserve author intent. Server-side conflict surfacing is required for contested ranges.
- **Editorial Workflow.** State machine that tracks drafts → review → publish → archive; permissions and audit logs wrap every transition.
- **Snapshots & Audit.** Immutable snapshots per publish event with accompanying audit records (actor, timestamp, diff summary).
- **Read & Search.** Hybrid full-text + vector search across snapshots; RAG answering uses search results as context.
- **Users & Roles.** JWT-authenticated actors with roles (reader, editor, reviewer, admin) that gate operations and log provenance.

## Module Overview
- **app/** – Ktor entrypoint, DI wiring, HTTP routes, security filters, and application configuration loader.
- **core/** – Domain models (documents, versions, embeddings), workflow state machine, and service interfaces for search, answers, and repositories.
- **infra/db/** – PostgreSQL + pgvector repositories (Exposed), Flyway migrations, and Testcontainers-backed integration tests.
- **infra/ai/** – AI client interfaces plus provider-specific implementations for embeddings and chat/generation.
- **infra/search/** – Hybrid search orchestration that combines tsvector ranking with vector similarity; applies re-ranking weights.
- **test/e2e/** – Black-box scenarios that validate ingestion, workflow transitions, search quality, and AI-assisted answering.
- **specs/** – Product and architectural specs (this directory) that serve as source-of-truth for behavior across modules.

## Cross-Cutting Concerns
- **Identity & attribution.** Propagate authenticated actor info through service calls; persist actor_id and rationale on mutations.
- **Immutability.** Snapshots and audit entries are append-only; deprecations mark records inactive without deletion.
- **Auditability.** Every state transition stores who did it, when, and why; include request IDs in logs and metrics for traceability.
- **Observability.** Standard metrics (latency, errors, rate limits) and structured logs with sensitive fields redacted at boundaries.

## How to Change the Specs
1. Open a PR that edits the relevant `.md` file(s) in this directory.
2. Link the motivating ticket and call out behavior changes in the PR summary.
3. Add or update tests and migration notes if the spec implies backend changes.
4. Secure a review from an owner of the affected area (collaboration, workflow, search/AI, or platform).
5. Merge only after reviewers confirm alignment between spec and implementation; keep older versions accessible via Git history.
