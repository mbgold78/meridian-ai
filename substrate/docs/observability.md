# Substrate observability — logs, metrics, traces

**Status:** Phase 5 Stage 3, landed. Targets production plan §8 ("structured logs + metrics + traces, shipped to per-engagement observability stack") on top of the Stage 1 auth posture (docs/auth.md) and the Stage 2 deploy artifacts (docs/deploy.md).

**Posture in one line:** everything is built in and **everything export-shaped is OFF by default** — a substrate process with default config emits structured logs to stdout and keeps metrics in memory; nothing leaves the process until an engagement flips `substrate.observability.enabled=true` and points it at *their* collector.

---

## 1. What ships

| Lane | Mechanism | Default |
|---|---|---|
| **Structured logs** | Logback + logstash JSON encoder to stdout (`logback-spring.xml`). Every line carries the MDC: `requestId` (set by `RequestIdFilter`, echoed as `X-Request-Id`), persona keys, and — when tracing is on — `traceId`/`spanId` from micrometer-tracing's context propagation. | Always on (stdout only; shipping is the environment's concern — awslogs/fluent-bit/etc.) |
| **Metrics** | Micrometer. Boot's system/JVM/HTTP meters plus the substrate engine inventory (§3), all tagged `engagement=<substrate.engagement.id>`. | Recorded in memory; **no export** |
| **Metric export** | `micrometer-registry-otlp` → OTLP/HTTP push to `<endpoint>/v1/metrics` every `step`. | **Off** (`substrate.observability.enabled=false` ⇒ no `OtlpMeterRegistry` bean) |
| **Traces** | micrometer-tracing → OpenTelemetry bridge (`micrometer-tracing-bridge-otel`), W3C `traceparent` propagation, OTLP/HTTP span export to `<endpoint>/v1/traces`. The inbound `requestId` is stamped on the server span as the `substrate.request_id` tag, so traces and the JSON log stream join (see §4). | **Off** (`management.tracing.enabled` maps to the keys below; off ⇒ no spans are started, no exporter bean exists) |

Pinned by tests: `ObservabilityExportToggleTest` (config-off ⇒ no OTLP beans; one key flips both lanes), `EngineMetricsTest` / `ContractGatewayMetricsTest` / `CostTrackerMetricsTest` (meter inventory + tag shapes), `Actuator*Test` (actuator exposure matrix, §5).

---

## 2. Configuration — `substrate.observability.*`

All keys live in `app/src/main/resources/application.yml` and are env-overridable; they fan out to the standard Boot `management.*` properties via placeholder mappings, so anything Boot supports remains reachable for advanced cases.

| Key | Env var | Default | Meaning |
|---|---|---|---|
| `substrate.observability.enabled` | `SUBSTRATE_OBSERVABILITY_ENABLED` | `false` | Master switch: OTLP metric export + (unless pinned below) tracing. |
| `substrate.observability.otlp.endpoint` | `SUBSTRATE_OBSERVABILITY_OTLP_ENDPOINT` | `http://localhost:4318` | Collector **base URL** (OTLP/HTTP); substrate appends `/v1/metrics` and `/v1/traces`. |
| `substrate.observability.otlp.step` | `SUBSTRATE_OBSERVABILITY_OTLP_STEP` | `60s` | Metric push interval. |
| `substrate.observability.tracing.enabled` | `SUBSTRATE_OBSERVABILITY_TRACING_ENABLED` | follows `enabled` | Pin tracing independently (e.g. metrics on, traces off). |
| `substrate.observability.tracing.sample-probability` | `SUBSTRATE_OBSERVABILITY_TRACE_SAMPLE` | `0.1` | Head sampling probability. |
| `substrate.observability.actuator.expose` | `SUBSTRATE_OBSERVABILITY_ACTUATOR_EXPOSE` | `false` | **Anonymous-mode, dev-only** opt-in that exposes `/actuator/metrics` without auth (§5). Never set in a customer engagement build. |

**Collector auth headers** (when the customer's collector requires them) use the standard Boot keys with env-refs — secrets never land in the repo (plan §8):

```
MANAGEMENT_OTLP_METRICS_EXPORT_HEADERS_AUTHORIZATION=Bearer ${COLLECTOR_TOKEN}
MANAGEMENT_OTLP_TRACING_HEADERS_AUTHORIZATION=Bearer ${COLLECTOR_TOKEN}
```

### Pointing substrate at a customer stack

The customer runs (or already has) an OpenTelemetry-compatible collector — an OTel Collector, Grafana Alloy, Datadog Agent with OTLP intake, etc. — **inside their own environment**. Substrate only ever pushes to a URL it is given:

```bash
SUBSTRATE_OBSERVABILITY_ENABLED=true
SUBSTRATE_OBSERVABILITY_OTLP_ENDPOINT=http://otel-collector.internal:4318
```

That is the entire integration surface. No vendor SDK, no Meridian endpoint, no code change per engagement.

---

## 3. The `substrate_*` metric inventory

Engine metrics are recorded through one facade — `engine/…/observability/EngineMetrics.java` is the single reviewable inventory. Dotted Micrometer names export as `substrate_*` under OTLP/Prometheus naming conventions.

| Meter (exported name) | Type | Tags | Recorded at |
|---|---|---|---|
| `substrate_contract_gateway_evaluations` | timer, p50/p99 published | `decision` (allow, allow_with_flag, deny, would_deny, allow_unevaluated), `mode` (off, shadow, enforce) | every `ContractGateway.evaluate` outcome (the PDP — engine-spec contracts.md §4.3) |
| `substrate_agent_gateway_denials` | counter | `reason` (e.g. `AGENT_LIFECYCLE_HUMAN_ONLY`, `DENY_GUARD_MATCHED`, `GATEWAY_ERROR`), `mode` | AGENT-principal deny tails, including shadow `WOULD_DENY` — watching this number is what shadow mode is for |
| `substrate_contract_monitor_runs` | counter | `outcome` (compliant, breach_detected, indeterminate, not_due, error, …) | each appended `contract_monitor_runs` row (`MonitorRunService`) |
| `substrate_contract_breaches_opened` | counter | `detected_by` (monitor, attestation, …) | NEW breach rows only — §5.4 re-confirms of an open breach do not count |
| `substrate_cascade_actions` | counter | `action` (issue, cascade, absorb, decline, recascade, resolve-looks-good, resolve-declined) | each appended cascade level action. **Decline is a legitimate action — never alert on it as an error** (port-map trap #8) |
| `substrate_agent_verbs` | counter | `verb` (`AgentEventType` wire values: deployed, paused, resumed, throttle-set, killed, promoted, intervention-opened, …) | the four agent verb write services at event append; demo seeding is excluded by construction |
| `substrate_diagnostic_runs` | counter | `status` (completed, completed_with_errors, zero_yield, failed, cancelled) | run finalization, exactly once per run, after the finalization tx commits |
| `substrate_diagnostic_findings_committed` / `substrate_diagnostic_candidates_filtered` | counters | — | per-run totals at finalization (the D6 grounding-gap signal: filtered ≫ committed) |
| `substrate_llm_calls` / `substrate_llm_tokens` / `substrate_llm_cost_estimated` | counters (`tokens`, `USD`) | `model`; tokens also `direction` (input/output) | the `CostTracker` bridge (S12) — token/cost telemetry survives restarts in the metrics backend even though the in-process `/llm/cost-summary` aggregate does not |

Plus everything Boot ships out of the box: `http_server_requests` (per endpoint/status latency), JVM, Hikari pool, logback counters — all carrying the `engagement` tag.

---

## 4. Log ↔ trace correlation

- `RequestIdFilter` (order 1) runs **inside** the server observation that Boot's `ServerHttpObservationFilter` opens, so when tracing is on it tags the current span with `substrate.request_id` — the same UUID in the `X-Request-Id` response header and the `requestId` MDC key of every log line for that request.
- micrometer-tracing's Slf4j bridge adds `traceId`/`spanId` to the MDC, and the logstash encoder emits all MDC keys — so the JSON log stream carries trace ids and the trace carries the request id. Either artifact finds the other.
- Reactive/SSE handlers keep correlation through the existing `MdcContextLifter` mechanics (see `RequestIdFilter` javadoc).

---

## 5. Actuator surface — locked down per auth mode

Exposure is computed by `ObservabilityEnvironmentPostProcessor` from `substrate.auth.mode`; an explicit `management.endpoints.web.exposure.include` (env var / profile YAML) always wins.

| Mode | `/actuator/health`, `/actuator/info` | `/actuator/metrics**` | everything else (`env`, `loggers`, …) |
|---|---|---|---|
| `anonymous` (dev default) | public — compose healthchecks and the ALB target group (deploy.md) depend on this | **404** (not exposed; exposed would mean public) | 404 |
| `anonymous` + `substrate.observability.actuator.expose=true` | public | public — **local debugging only, never a customer build** | 404 |
| `oidc` (production) | public | **401** without a token, **403** without an admin role, 200 with a verified JWT carrying one of `substrate.auth.oidc.admin-roles` — the same roles-claim mapping (`JwtRoleExtractor`) the product admin gates use | 404 |

Note the production metrics path is **push** (OTLP) — the actuator metrics endpoint is an admin convenience, not the export mechanism, so nothing needs to scrape the app.

---

## 6. Per-engagement isolation posture

- **One engagement = one deploy = one observability tail.** Substrate pushes telemetry only to the collector configured for that deployment, which lives in the customer's environment next to the app (plan §3: customer-env is production; no customer data ever transits Meridian infra — telemetry included, since logs/traces can carry customer data).
- Every meter is tagged `engagement=<substrate.engagement.id>` and trace resources carry the same attribute (`management.opentelemetry.resource-attributes.engagement`), so even a customer aggregating multiple engagement deploys into one stack can prove separation.
- There is **no cross-engagement code path**: no shared Meridian collector endpoint exists in any configuration, default or otherwise.
- AWS reference shape (Stage 2 artifacts, `infra/aws/modules/observability` — **artifacts + docs only, nothing provisioned during build**): CloudWatch-native baseline — awslogs ships the JSON stdout stream, Container Insights covers infra metrics, alarms/dashboard per engagement. App-level OTLP export remains available on top wherever the customer runs a collector (e.g. ADOT sidecar → AMP/X-Ray), by setting the two env vars in the task definition.

## 7. Local dev / verification

- This dev machine: everything off by default; `gradlew :app:bootRun` behaves exactly as before Stage 3. To poke at meters locally: `--substrate.observability.actuator.expose=true` then `GET /actuator/metrics/substrate.cascade.actions`.
- No Docker locally ⇒ no local collector; OTLP wiring is verified by the context-runner test (`ObservabilityExportToggleTest`) and, in Docker-capable environments, by adding an `otel/opentelemetry-collector` service to `docker-compose.yml` and setting the two env vars on `substrate-app`.
- Zero overhead claim, precisely: config-off means no exporter beans, no tracer spans, no scheduled pushes; engine meters still increment in-memory registries (nanoseconds per event) — that is what `/actuator/metrics` reads when an admin opts in.
