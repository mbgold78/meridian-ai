# Meridian Substrate

Production substrate for AI-leveraged portfolio governance. Sibling product to `meridian-ai/orchestrator`.

## What this is

Substrate is the governance layer Meridian deploys into a customer's environment during a holding-company transformation engagement. It runs:

- Durable agent identity + oversight-chain enforcement
- Cascading evaluation (leader critique propagates downward; Decline is a first-class upward response)
- MCP-enforced coordination contracts (cross-subsidiary commitments the substrate guarantees)
- A canonical org/system/process model populated from customer data
- Diagnostic findings, capability design, transformation planning surfaces

It is **not** a multi-tenant SaaS. Each engagement deploys substrate into the customer's AWS account (or other infrastructure). Modules deploy independently — whole or in part — matched to engagement need.

## Relationship to `orchestrator`

Substrate calls into orchestrator (the sibling product) when work needs to be done by AI agents — diagnostic generation, finding curation, cascade-level execution. The seam is MCP. Substrate runs standalone for engagements that don't need agent automation.

See `docs/orchestrator-substrate-contract.md` for the seam definition.

## Status

**Phase 0 scaffold complete.** Multi-module Gradle build green (Spring Boot 3.4.4 / Java 21), dev profile boots with zero external dependencies, Docker Compose stack (app + Postgres + Keycloak) defined, CI on GitHub Actions. See `docs/dev-setup.md` to run it.

See `../orchestrator/docs/plan/substrate-production-plan.md` for the canonical build plan.

## Modules (Spring Boot multi-module)

- `app` — main Spring Boot application (web layer, controllers, services)
- `common` — shared domain model, DTOs, AI client interfaces
- `ingestion` — ingest customer data (CSV/JSON/text → canonical entities)
- `engine` — cascading evaluation engine, coordination contract enforcement

Frontend will live under `frontend/` (mirror homepoc structure once port begins).

## Local dev

Fully operational on Windows with zero external dependencies (`gradlew :app:bootRun`, dev profile) or via Docker Compose for the full Postgres + Keycloak stack. **No AWS resources required during build.** Cloud deploy tooling spins up only for cloud-validation work or real customer packaging.

See `docs/dev-setup.md`.
