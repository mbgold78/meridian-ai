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
| keycloak | 8081 | dev IdP, realm `substrate` auto-imported with users `dev-operator` / `dev-exec` / `dev-coo` (password = username) |

Keycloak is the **dev/testing IdP only**. Production deploys connect to the customer's IdP via generic OIDC (production plan §4).

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
