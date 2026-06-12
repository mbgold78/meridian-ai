# Task 1 — First boot: empty vs seeded, profiles, ports

What this task teaches: how to boot Substrate from a built jar, how to tell
an *empty* engagement from a *seeded demo* one, which Spring profile to pick,
and how to put the server on the port you want. Evidence transcript:
`substrate/docs/acceptance/doc-1/s4-operator/transcript-1-first-boot.txt`.

## Pick a profile first

| Profile | Database | Sign-in | Use it for |
|---|---|---|---|
| `dev` (default) | In-memory H2 | None (anonymous operator identity) | Day-to-day local use and demos. Data resets on every restart. |
| `dev-shared` | File-based H2 at `substrate/data/` | None | Running the ingestion CLI and the app against **one shared database** (tasks 2–3). Reset by deleting `substrate/data/`. |
| `postgres` | PostgreSQL | OIDC | The Compose stack and customer environments. |

## Steps

1. Build the jar once:

   ```powershell
   cd substrate
   .\gradlew.bat :app:bootJar
   ```

2. **Empty boot** — no demo flags. This is what a real engagement looks like
   on day zero: the product starts from ingestion, not from a seed.

   ```powershell
   java -jar app\build\libs\app-0.1.0.jar --spring.profiles.active=dev --server.port=53181
   ```

   The default port is 8080; `--server.port=<n>` moves it — pick any free
   port, nothing else changes. Boot log lines to expect:

   ```text
   The following 1 profile is active: "dev"
   Tomcat started on port 53181 (http) with context path '/'
   Started SubstrateApplication in 10.507 seconds (process running for 11.015)
   ```

3. Confirm the server is up, then confirm the workspace is **empty** — these
   are different facts:

   ```powershell
   curl -s http://localhost:53181/actuator/health
   curl -s http://localhost:53181/api/v1/domains
   ```

   ```text
   {"status":"UP","groups":["liveness","readiness"]}
   {
       "data": [],
       "meta": { ... "personaLevel": "L3_ANALYST" },
       "errors": []
   }
   ```

   Health `UP` means the app answers HTTP. It does **not** mean data exists,
   and during a seeded boot it goes UP while the seed transaction is still
   open. Readiness for seeded work is always the domain count.

4. Stop that instance. **Seeded boot** — the canonical demo posture:

   ```powershell
   java -jar app\build\libs\app-0.1.0.jar --spring.profiles.active=dev `
     --substrate.demo.seed=true --substrate.demo.contracts.activate=true `
     --substrate.demo.fixed-clock=2026-05-12T00:00:00Z --server.port=8080
   ```

   The seed runs in one outer transaction, 14 seeders in dependency order,
   one log line per seeder — this is how you confirm what landed without
   writing SQL:

   ```text
   [seed] Registered 14 seeder(s): [DomainSeeder, AgentDemoSeeder, BrandingSeeder, ContractSeeder, OrganizationSeeder, CascadeDemoSeeder, PhaseSeeder, ReaderSeeder, SystemSeeder, ProcessSeeder, CanonicalEntitySeeder, CapabilitySeeder, DiagnosticSeeder, EvidenceSeeder]
   [seed] Fresh DB — starting bulk seed
   [seed] DomainSeeder: 1 demo domain [id=…, name='Operational Risk', isDemo=true]
   [seed] AgentDemoSeeder: 22 satellite domains created, 70 agents seeded via the lifecycle verb path, 0 already present (idempotent re-entry)
   [seed]   activate lever: co-tribal-knowledge-depopulation is ACTIVE [activatedAt=2026-05-12T00:00:00Z]
   [seed] CascadeDemoSeeder: 3 of 3 fixture cascades seeded via the lifecycle verb path [domainId=…]
   [seed] DiagnosticSeeder: 21 findings, 5 decisions
   [seed] Bulk seed complete [domainId=…]
   DEMO LEVER ACTIVE: engine clock FIXED at 2026-05-12T00:00:00Z (substrate.demo.fixed-clock; S15 demo determinism — never enable in a customer engagement build)
   ```

   The `DEMO LEVER ACTIVE` warning is deliberate: the fixed clock makes every
   time-based computation wrong on purpose. None of the `substrate.demo.*`
   flags belongs in a customer engagement build.

5. Wait for seed readiness **by polling the API, never by sleeping** — the
   count flips 0 → 23 only when the whole seed transaction has committed:

   ```powershell
   curl -s http://localhost:8080/api/v1/domains    # repeat until data has 23 rows
   ```

6. Open `http://localhost:8080` in a browser. You land on the Living Canvas —
   the seeded engagement's systems and processes as an interactive map:

{{capture:living-canvas}}

## You'll know it worked when…

…the empty boot answers `GET /api/v1/domains` with `"data": []` while health
says UP, and the seeded boot answers with **23 rows** (1 primary engagement
domain + 22 Beacon satellites) — the metric that moved is the domain count,
0 → 23 — and the Living Canvas at `/` renders tiles instead of an empty
state.
