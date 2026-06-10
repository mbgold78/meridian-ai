# Frontend scripts

One-off tooling that doesn't belong in the build, the test runner, or
the app itself. Run from the `substrate/frontend/` directory unless noted.

---

## `npm run screenshots`

Captures full-page screenshots of every Beacon portfolio route plus the
standard-product baseline routes across two viewport sizes. Output lands
at the substrate root in
`demo-screenshots/<timestamp>/<viewport>/<route>.png`.

Routes captured (chunk C9 — product route namespace):

| Route | Bundle filename |
|---|---|
| `/portfolio` | `portfolio-landing.png` |
| `/portfolio/dashboard` | `portfolio-operating-partner.png` |
| `/portfolio/subsidiary/<first-slug>?tab=capabilities` | `portfolio-subsidiary-<slug>-capabilities.png` |
| `/portfolio/subsidiary/<first-slug>?tab=environment` | `portfolio-subsidiary-<slug>-environment.png` |
| `/portfolio/subsidiary/<first-slug>?tab=plan` | `portfolio-subsidiary-<slug>-plan.png` |
| `/portfolio/subsidiary/<first-slug>?tab=operations` | `portfolio-subsidiary-<slug>-operations.png` |
| `/portfolio/cascade/c-vendor-cost` | `portfolio-cascade-c-vendor-cost.png` |
| `/portfolio/contract/co-tribal-knowledge-depopulation` | `portfolio-contract-co-tribal-knowledge-depopulation.png` |
| `/` | `baseline-home.png` |
| `/designer/capabilities` | `baseline-capability-designer.png` |

Viewports: `desktop` (1440×900) and `mobile` (390×844). Total: 20 PNGs
per run.

> The portfolio routes require the app router to mount
> `PORTFOLIO_ROUTES` from `@/features/portfolio` (chunk C8 wires
> App.tsx/main.tsx). Until C8 lands, the routes fall through to the SPA
> fallback and the captures show the shell placeholder.

### Prerequisites (one-time)

```bash
cd substrate/frontend
npm install                    # picks up @playwright/test + tsx as dev deps
npx playwright install chromium
```

### Running

The script **does not boot the app** — it assumes the app is already
serving at `http://localhost:8080` (Spring Boot's default). Override
with `SCREENSHOT_BASE_URL` if you point it elsewhere (e.g. the Vite dev
server at `http://localhost:5173`).

```bash
# From substrate/frontend/
npm run screenshots
```

### ⚠️ Bundle staleness — read before running after a frontend change

The frontend pipeline has two stages that need to be in sync for Spring
Boot to serve fresh code:

1. **Vite build writes to** `app/src/main/resources/static/`
2. **Spring Boot serves from** `app/build/resources/main/static/`

A vanilla `npm run build` updates step 1 but not step 2 — the running
Spring Boot process keeps serving the old bundle from its build/
classpath. Result: screenshots that look correct because they reflect a
*previous* version of the frontend. (Port-map §2.5 documents this trap;
chunk C11 bakes frontend→processResources into Gradle.)

**Workflow that captures fresh bundles:**

```bash
# 1. Rebuild the Vite bundle into the gradle source set.
cd substrate/frontend
npm run build

# 2. Sync the bundle into the gradle build classpath.
#    `processResources` copies src/main/resources -> build/resources/main.
cd ../..
./gradlew :app:processResources

# 3. (Restart Spring Boot if not already on bootRun with hot-reload.)

# 4. Capture.
cd substrate/frontend
npm run screenshots
```

If you skip step 2 the screenshots will silently lie about what the
frontend currently looks like — verify by hitting one of the captured
routes in a browser and comparing.

### Pointing at the Vite dev server instead

If you're iterating on visual changes and don't want the gradle
round-trip, run Vite directly and aim the script at it:

```bash
cd substrate/frontend
npm run dev          # serves at http://localhost:5173
SCREENSHOT_BASE_URL=http://localhost:5173 npm run screenshots
```

The dev server proxies `/api` to Spring Boot, so the portfolio routes
(which make zero API calls beyond the ThemeProvider branding fetch)
work without most of the backend. The baseline routes need Spring Boot
up to return real data.
