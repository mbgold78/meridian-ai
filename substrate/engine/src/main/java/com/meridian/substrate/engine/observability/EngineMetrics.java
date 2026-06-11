package com.meridian.substrate.engine.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The engine's Micrometer facade — every {@code substrate_*} engine metric is
 * named, tagged and registered HERE so the metric surface stays a single
 * reviewable inventory (production plan §8; Phase 5 Stage 3).
 *
 * <p><b>Meter inventory (dotted names; OTLP/Prometheus naming conventions
 * render them with the {@code substrate_} prefix):</b>
 * <ul>
 *   <li>{@code substrate.contract.gateway.evaluations} — TIMER, tags
 *       {@code decision} (allow | allow_with_flag | deny | would_deny |
 *       allow_unevaluated) and {@code mode} (off | shadow | enforce);
 *       p50/p99 published. One record per {@code ContractGateway.evaluate}.</li>
 *   <li>{@code substrate.agent.gateway.denials} — counter, tags {@code reason}
 *       and {@code mode}: AGENT-principal denials (built-in guards, registry
 *       gate, clause guards). Shadow {@code WOULD_DENY} counts here too — the
 *       whole point of shadow is watching this number.</li>
 *   <li>{@code substrate.contract.monitor.runs} — counter, tag {@code outcome}
 *       ({@code MonitorRunOutcome}, lowercase).</li>
 *   <li>{@code substrate.contract.breaches.opened} — counter, tag
 *       {@code detected_by}; NEW breach rows only (a §5.4 re-confirm of an
 *       open breach is not an "open").</li>
 *   <li>{@code substrate.cascade.actions} — counter, tag {@code action}
 *       ({@code CascadeActionType} wire value). DECLINE counts like any other
 *       legitimate action — never an error signal (port-map trap #8).</li>
 *   <li>{@code substrate.agent.verbs} — counter, tag {@code verb}
 *       ({@code AgentEventType} wire value); recorded by the four §5.5 verb
 *       write services (lifecycle, promotion, intervention, control-confirm)
 *       at event append. Engine-internal observation events (gateway signal,
 *       dispatch preflight, criteria standing) are deliberately NOT verbs.</li>
 *   <li>{@code substrate.diagnostic.runs} — counter, tag {@code status}
 *       (terminal statuses only, recorded once at finalization).</li>
 *   <li>{@code substrate.diagnostic.findings.committed} /
 *       {@code substrate.diagnostic.candidates.filtered} — counters,
 *       incremented by the per-run totals at finalization.</li>
 *   <li>{@code substrate.llm.calls} / {@code substrate.llm.tokens}
 *       (tag {@code direction}=input|output) /
 *       {@code substrate.llm.cost.estimated} (USD) — the {@code CostTracker}
 *       bridge (S12); all tagged {@code model}.</li>
 * </ul>
 *
 * <p><b>Wiring posture:</b> instrumented services keep their pre-Stage-3
 * constructors (existing tests construct them by hand) and gain an
 * {@code @Autowired} overload taking {@code ObjectProvider<EngineMetrics>} —
 * engine test slices that scan service packages without this one still wire
 * fine ({@link #noop()} fallback). In the app context this class is a normal
 * component backed by Boot's autoconfigured {@link MeterRegistry}; recording
 * into it is in-memory only — whether anything LEAVES the process is the app
 * module's {@code substrate.observability.*} OTLP export decision
 * (off by default).
 */
@Component
public class EngineMetrics {

    public static final String GATEWAY_EVALUATIONS = "substrate.contract.gateway.evaluations";
    public static final String AGENT_GATEWAY_DENIALS = "substrate.agent.gateway.denials";
    public static final String MONITOR_RUNS = "substrate.contract.monitor.runs";
    public static final String BREACHES_OPENED = "substrate.contract.breaches.opened";
    public static final String CASCADE_ACTIONS = "substrate.cascade.actions";
    public static final String AGENT_VERBS = "substrate.agent.verbs";
    public static final String DIAGNOSTIC_RUNS = "substrate.diagnostic.runs";
    public static final String DIAGNOSTIC_FINDINGS_COMMITTED = "substrate.diagnostic.findings.committed";
    public static final String DIAGNOSTIC_CANDIDATES_FILTERED = "substrate.diagnostic.candidates.filtered";
    public static final String LLM_CALLS = "substrate.llm.calls";
    public static final String LLM_TOKENS = "substrate.llm.tokens";
    public static final String LLM_COST_ESTIMATED = "substrate.llm.cost.estimated";

    /** Decision tag value for mode=off evaluations (no {@code GatewayDecision} row exists). */
    public static final String DECISION_ALLOW_UNEVALUATED = "allow_unevaluated";

    private static final String UNKNOWN = "unknown";

    private final MeterRegistry registry;

    /**
     * Spring constructor: Boot's autoconfigured registry when present (the
     * app module always has actuator), a throwaway in-memory registry
     * otherwise — partial engine test contexts never fail to wire.
     */
    @Autowired
    public EngineMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this(registryProvider.getIfAvailable(SimpleMeterRegistry::new));
    }

    public EngineMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Sink instance for hand-constructed services (legacy constructors, demo
     * seeders — demo fixture traffic deliberately stays OUT of ops metrics).
     */
    public static EngineMetrics noop() {
        return new EngineMetrics(new SimpleMeterRegistry());
    }

    // ------------------------------------------------------------ contract PDP

    /** One per {@link com.meridian.substrate.engine.contract.gateway.ContractGateway#evaluate}. */
    public void gatewayEvaluation(String decision, String mode, long startedNanos) {
        Timer.builder(GATEWAY_EVALUATIONS)
                .description("Contract gateway (PDP) evaluations by decision and enforcement mode")
                .tag("decision", lower(decision))
                .tag("mode", lower(mode))
                .publishPercentiles(0.5, 0.99)
                .register(registry)
                .record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
    }

    public void agentGatewayDenial(String reason, String mode) {
        Counter.builder(AGENT_GATEWAY_DENIALS)
                .description("Gateway denials (DENY/WOULD_DENY) of AGENT-principal actions")
                .tag("reason", reason == null ? UNKNOWN : reason)
                .tag("mode", lower(mode))
                .register(registry)
                .increment();
    }

    // -------------------------------------------------------- contract monitors

    public void monitorRun(String outcome) {
        Counter.builder(MONITOR_RUNS)
                .description("Contract monitor runs by outcome")
                .tag("outcome", lower(outcome))
                .register(registry)
                .increment();
    }

    public void breachOpened(String detectedBy) {
        Counter.builder(BREACHES_OPENED)
                .description("New contract breaches opened (re-confirmations excluded)")
                .tag("detected_by", lower(detectedBy))
                .register(registry)
                .increment();
    }

    // ----------------------------------------------------------------- cascade

    public void cascadeAction(String action) {
        Counter.builder(CASCADE_ACTIONS)
                .description("Cascade level actions appended, by action type"
                        + " (decline is a legitimate action, never an error)")
                .tag("action", lower(action))
                .register(registry)
                .increment();
    }

    // ------------------------------------------------------------------- agent

    public void agentVerb(String verb) {
        Counter.builder(AGENT_VERBS)
                .description("Agent verb events appended by the agent write services")
                .tag("verb", lower(verb))
                .register(registry)
                .increment();
    }

    // -------------------------------------------------------------- diagnostic

    public void diagnosticRun(String terminalStatus) {
        Counter.builder(DIAGNOSTIC_RUNS)
                .description("Diagnostic runs finalized, by terminal status")
                .tag("status", lower(terminalStatus))
                .register(registry)
                .increment();
    }

    public void diagnosticFindingsCommitted(int count) {
        if (count > 0) {
            Counter.builder(DIAGNOSTIC_FINDINGS_COMMITTED)
                    .description("Findings committed by finalized diagnostic runs")
                    .register(registry)
                    .increment(count);
        }
    }

    public void diagnosticCandidatesFiltered(int count) {
        if (count > 0) {
            Counter.builder(DIAGNOSTIC_CANDIDATES_FILTERED)
                    .description("Finding candidates filtered (grounding/shape) by finalized"
                            + " diagnostic runs")
                    .register(registry)
                    .increment(count);
        }
    }

    // --------------------------------------------------------------------- LLM

    /** The CostTracker bridge (port-map S12): one call per LLM invocation. */
    public void llmUsage(String model, int inputTokens, int outputTokens, double estimatedCostUsd) {
        String modelTag = model == null || model.isBlank() ? UNKNOWN : model;
        Counter.builder(LLM_CALLS)
                .description("LLM calls recorded by CostTracker")
                .tag("model", modelTag)
                .register(registry)
                .increment();
        Counter.builder(LLM_TOKENS)
                .description("LLM tokens recorded by CostTracker")
                .tag("model", modelTag)
                .tag("direction", "input")
                .baseUnit("tokens")
                .register(registry)
                .increment(inputTokens);
        Counter.builder(LLM_TOKENS)
                .description("LLM tokens recorded by CostTracker")
                .tag("model", modelTag)
                .tag("direction", "output")
                .baseUnit("tokens")
                .register(registry)
                .increment(outputTokens);
        if (estimatedCostUsd > 0) {
            Counter.builder(LLM_COST_ESTIMATED)
                    .description("Estimated LLM spend from configured substrate.llm.cost rates")
                    .tag("model", modelTag)
                    .baseUnit("USD")
                    .register(registry)
                    .increment(estimatedCostUsd);
        }
    }

    private static String lower(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value.toLowerCase(Locale.ROOT);
    }
}
