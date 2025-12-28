# Users and Roles Specification

Defines identity, role-based authorization, and user management for MessageSearch. The module assumes authentication is handled by JWTs issued by a trusted identity provider; this service focuses on authorization and auditing.

## Roles
- **Reader:** Can read published snapshots and search.
- **Editor:** Can create and edit drafts, submit reviews, and view audit history on owned drafts.
- **Reviewer:** All editor permissions plus approve/reject drafts and request changes.
- **Admin:** All reviewer permissions plus force-publish, archive, and manage role assignments.

Role evaluation is additive: a user with multiple roles receives the union of permissions. Admin supersedes all roles.

## JWT Expectations
JWTs must include:
- `sub`: stable user ID (UUID string).
- `roles`: array of role strings (`reader`, `editor`, `reviewer`, `admin`).
- `iss` and `aud` must match configured values.

Missing or invalid claims return `401 Unauthorized`. Insufficient roles return `403 Forbidden`.

## Data Model
Persist users for attribution and audit trails even if role assignments are managed externally.

```text
users
- id (uuid, pk)
- email (text, unique, nullable)
- display_name (text, nullable)
- status (text, enum: active, disabled)
- created_at (timestamptz)
- updated_at (timestamptz)

user_roles
- user_id (uuid, fk -> users.id)
- role (text, enum: reader, editor, reviewer, admin)
- assigned_by (uuid, fk -> users.id)
- assigned_at (timestamptz)
```

Notes:
- `user_roles` is append-only; revocations are recorded in an audit table (see below).
- Email is optional to support external identity providers without email claims.

## API Expectations (minimum set)
- `GET /v1/users/me` – return the authenticated user profile and roles.
- `GET /v1/users` – list users (Admin only, paginated).
- `POST /v1/users` – create user (Admin only).
- `PATCH /v1/users/{id}/roles` – replace role assignments (Admin only).
- `PATCH /v1/users/{id}/status` – enable/disable user (Admin only).
- `GET /v1/users/{id}` – fetch user profile (Admin only; self access allowed).

Each endpoint must:
- Validate JWT roles.
- Return `401` if missing/invalid JWT, `403` if insufficient role.
- Emit audit events for mutations (role/status changes, user creation).

### Request/Response Shapes
```json
// POST /v1/users
{ "email": "user@example.com", "displayName": "Example User", "roles": ["editor"] }

// PATCH /v1/users/{id}/roles
{ "roles": ["reviewer", "editor"], "reason": "promoted for review coverage" }

// PATCH /v1/users/{id}/status
{ "status": "disabled", "reason": "left company" }

// GET /v1/users/me
{ "id": "uuid", "email": "user@example.com", "displayName": "Example User", "roles": ["editor"], "status": "active" }
```

## Auditing
All mutations write audit records with:
- `audit_id`, `actor_id`, `target_user_id`, `action`, `reason`, `created_at`.
- `action` values: `user.created`, `roles.replaced`, `status.changed`.

Expose `GET /v1/users/{id}/audits?limit&cursor` for admin-only access.

## Authorization Matrix
- Reader: search, read published snapshots.
- Editor: reader + draft create/edit/review submit.
- Reviewer: editor + approve/reject/request-changes.
- Admin: reviewer + role management + status toggles + force publish + archive.

## Non-Goals
- Password-based auth, MFA, or token issuance.
- Multi-tenant ACLs; roles are single-tenant and coarse-grained.

