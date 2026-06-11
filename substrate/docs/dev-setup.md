# Substrate — local dev setup

Two local shapes. Both run entirely on the workstation; **no AWS resources during build** (production plan §3).

## Shape 1 — zero dependencies (no Docker)

Requirements: Java 21, Node 20+.

```powershell
# Backend — dev profile: H2 in PostgreSQL-compatibility mode, Flyway on, anonymous auth
cd substrate
.\gradlew.bat :app:bootRun

# Frontend (separate terminal)
cd substrate\frontend
npm install
npm run dev          # Vite dev server on :5173, proxies /api to :8080
```

- API: http://localhost:8080 — OpenAPI UI at `/swagger-ui.html`, health at `/actuator/health`
- H2 console (dev only): http://localhost:8080/h2-console
- Auth runs in `anonymous` mode; every request gets a synthetic operator identity.

## Shape 2 — full stack (Docker)

Requirements: Docker (Compose v2).

```powershell
cd substrate
copy .env.example .env    # then edit passwords
docker compose up --build
```

Brings up:

| Service | Port | Notes |
|---|---|---|
| substrate-app | 8080 | `postgres` profile, real OIDC against Keycloak |
| postgres | 5432 | engagement database, Flyway-managed |
| keycloak | 8081 | dev IdP, realm `substrate` auto-imported with users `dev-admin` / `dev-operator` / `dev-exec` / `dev-coo` (password = username) + service client `substrate-agent` for MCP client-credentials |

Keycloak is the **dev/testing IdP only**. Production deploys connect to the customer's IdP via generic OIDC (production plan §4). The full auth-mode ladder (claim mappings, persona projection, MCP principal binding, SSE token transport, verification lanes) is documented in [docs/auth.md](auth.md).

## Tests

```powershell
.\gradlew.bat build            # compiles + unit + integration tests, all modules
cd frontend
npm run type-check; npm run lint; npm test
```

## Profiles

| Profile | Database | Auth | Use |
|---|---|---|---|
| `dev` (default) | H2, PostgreSQL mode | anonymous | day-to-day dev without Docker |
| `postgres` | Postgres via `SUBSTRATE_DB_*` env | OIDC (`SUBSTRATE_AUTH_MODE` overridable) | compose stack, testing deploys, customer environments |

Flyway migrations live in `app/src/main/resources/db/migration/common` (ANSI-portable) plus `db/migration/postgresql` / `db/migration/h2` for dialect-specific DDL.

## Ingestion CLI (chunk C6)

The ingestion module is a standalone boot CLI (no web server) that converts
customer source files (CSV / JSON / text) into canonical entities:
`convert → stage (human review) → commit`. A fictional sample corpus
("Glacier Peak Credit Union") is checked in at `ingestion/samples/`.

Run it end-to-end with zero external dependencies (dev profile = file-based H2
under `ingestion/data/`, so commits persist across invocations; the CLI runs
Flyway itself from the shared migration classpath):

```powershell
cd substrate

# 1. Convert + stage the sample corpus. With no ANTHROPIC_API_KEY, use the
#    deterministic mock provider (row-echo extraction — exercises the full
#    pipeline mechanics without a network call). Manual-process CSVs convert
#    deterministically and never need the LLM either way.
.\gradlew.bat :ingestion:bootRun --args="convert-and-stage --input samples --substrate.llm.provider=mock"

# 2. Review ingestion/staging/*.json, then commit (creates/upserts entities
#    in the target domain, default name 'Ingested'; override with --domain).
.\gradlew.bat :ingestion:bootRun --args="commit"

# 3. Idempotency check: re-run commit — every staged file is sha256-skipped,
#    0 created / 0 updated, identical row counts.
.\gradlew.bat :ingestion:bootRun --args="commit"
```

With a real key, drop the provider override: `$env:ANTHROPIC_API_KEY = '...'`
then `convert-and-stage --input samples`.

Commands: `convert`, `stage`, `commit`, `convert-and-stage`. Flags: `--input`,
`--output`, `--staging`, `--domain`. Every stage emits a single-line
machine-readable `INGEST_EVENT {...}` summary and writes an `ingest_runs`
audit row; source and staged files are registered in `document_registry` and
stamped with `processed_by_converter_run_id`.

Exit codes: `0` success · `1` errors during processing · `2` usage error ·
`3` **zero-yield hard fail** (the run consumed nothing — empty/wrong directory,
nothing converted/staged/committed and nothing legitimately deduplicated).
A fully-deduplicated re-run exits 0.

Reset the dev database: delete `ingestion/data/` (plus `ingestion/output/` and
`ingestion/staging/` for a fully clean slate).

Against the shared Postgres stack: `--spring.profiles.active=postgres` with the
same `SUBSTRATE_DB_*` env vars as the app module.

Tests: `.\gradlew.bat :ingestion:test` — includes the end-to-end
convert→stage→commit→re-commit pin over `ingestion/samples/` (mock provider,
Flyway-migrated H2) plus CLI exit-code and routing/kind-resolution pins.
