# Web App

Developer guide for the web frontend. Product requirements live in `apps/web/SPEC.md`.

## Status
- Planned: Next.js + TypeScript (App Router)
- This folder is currently a placeholder; no frontend code yet.

## Getting Started (once implemented)
- Install dependencies and run the web dev server from `apps/web`.
- Start the backend with `./gradlew :backend:run` so the APIs are reachable.
- Copy `apps/web/.env.example` to `apps/web/.env.local` and adjust values.

```bash
cd apps/web
npm install
npm run dev
```

## Project Notes
- Product/UI requirements: `apps/web/SPEC.md`.
- API behavior and payloads: `specs/`.
