package com.meridian.substrate.engine.contract;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Contract engine configuration ({@code substrate.contracts.*}) — engine-spec
 * contracts.md §1; ADR-002. Only the keys consumed by shipped behavior are
 * bound here (3a: types/clock; 3b: monitor sweep + trace cap; 3c-1:
 * enforcement.mode + decision-log.level — the ADR D4 mode ladder and the ADR
 * D17 row-volume policy consumed by {@code ContractGateway}); the remaining
 * §1 key (MCP mount) binds in the chunk that consumes it — the YAML defaults
 * are already documented in {@code application.yml}.
 * {@code substrate.contracts.orchestrator-remediation} is consumed directly
 * via {@code @ConditionalOnProperty} on the §11.1 hook bean.
 */
@Component
@ConfigurationProperties(prefix = "substrate.contracts")
public class ContractProperties {

    private Types types = new Types();
    private ClockProps clock = new ClockProps();
    private Monitor monitor = new Monitor();
    private Trace trace = new Trace();
    private Enforcement enforcement = new Enforcement();
    private DecisionLog decisionLog = new DecisionLog();

    public Types getTypes() {
        return types;
    }

    public void setTypes(Types types) {
        this.types = types;
    }

    public ClockProps getClock() {
        return clock;
    }

    public void setClock(ClockProps clock) {
        this.clock = clock;
    }

    public Monitor getMonitor() {
        return monitor;
    }

    public void setMonitor(Monitor monitor) {
        this.monitor = monitor;
    }

    public Trace getTrace() {
        return trace;
    }

    public void setTrace(Trace trace) {
        this.trace = trace;
    }

    public Enforcement getEnforcement() {
        return enforcement;
    }

    public void setEnforcement(Enforcement enforcement) {
        this.enforcement = enforcement;
    }

    public DecisionLog getDecisionLog() {
        return decisionLog;
    }

    public void setDecisionLog(DecisionLog decisionLog) {
        this.decisionLog = decisionLog;
    }

    /**
     * {@code substrate.contracts.monitor.*} — §1: {@code enabled} is the
     * master switch for the scheduler sweep; {@code sweep-interval} is the
     * fixed-delay of the sweep (claims monitors whose
     * {@code next_run_at <= now}).
     */
    public static class Monitor {

        private boolean enabled = true;
        private java.time.Duration sweepInterval = java.time.Duration.ofSeconds(60);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public java.time.Duration getSweepInterval() {
            return sweepInterval;
        }

        public void setSweepInterval(java.time.Duration sweepInterval) {
            this.sweepInterval = sweepInterval;
        }
    }

    /**
     * {@code substrate.contracts.trace.max-bytes} — §1: cap on persisted
     * monitor-run trace JSON; truncated with a {@code "truncated": true}
     * marker (ADR D10 evaluation-trace graft).
     */
    public static class Trace {

        private int maxBytes = 16384;

        public int getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(int maxBytes) {
            this.maxBytes = maxBytes;
        }
    }

    /** {@code substrate.contracts.types.allowlist} — validated allowlist for {@code contract_type} (ADR D15). */
    public static class Types {

        private List<String> allowlist = List.of("cross-portfolio-opportunity", "federation-invitation");

        public List<String> getAllowlist() {
            return allowlist;
        }

        public void setAllowlist(List<String> allowlist) {
            this.allowlist = allowlist;
        }
    }

    /**
     * {@code substrate.contracts.enforcement.mode} — §1/ADR D4 ladder
     * ({@code off | shadow | enforce}), the {@code ContractGateway} PDP
     * behavior switch (read at evaluate time — per-engagement flips need no
     * restart). Also stamped on the §6 decline advisory {@code
     * ALLOW_WITH_FLAG} decision rows (D17: those rows are always rowed).
     */
    public static class Enforcement {

        private String mode = "shadow";

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }
    }

    /**
     * {@code substrate.contracts.decision-log.level} — §1/ADR D17 row-volume
     * policy: {@code matched-only} (default) rows only decisions where ≥1
     * guard matched (plus all FLAG/DENY/WOULD_DENY — those are always rowed);
     * plain no-guard/no-match ALLOWs are metrics counters. {@code all} rows
     * everything. Consumed by the 3c {@code ContractGateway}.
     */
    public static class DecisionLog {

        private String level = "matched-only";

        public String getLevel() {
            return level;
        }

        public void setLevel(String level) {
            this.level = level;
        }
    }

    /**
     * {@code substrate.contracts.clock.zone} — zone for period-key computation
     * (§5.1, consumed by the 3b evaluators; bound here because it is part of
     * the engine's clock contract).
     */
    public static class ClockProps {

        private String zone = "UTC";

        public String getZone() {
            return zone;
        }

        public void setZone(String zone) {
            this.zone = zone;
        }
    }
}
