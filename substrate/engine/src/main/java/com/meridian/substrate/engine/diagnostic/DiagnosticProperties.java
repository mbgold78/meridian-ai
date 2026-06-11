package com.meridian.substrate.engine.diagnostic;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code substrate.diagnostic.*} configuration (engine-spec diagnostic.md §2).
 * Defaults mirror the spec table; every value is a first-engagement
 * calibration item, not an invariant.
 *
 * <p>EXTENSION POINT(diagnostic-5): {@code auto-after-ingest} is NAMED but NOT
 * built in v1 — the pipeline is operator-paced (spec §15.5). The key exists so
 * the decision is visible in configuration; no code reads it yet. Flag, don't
 * invent.
 */
@Component
@ConfigurationProperties(prefix = "substrate.diagnostic")
public class DiagnosticProperties {

    public static final String EXECUTOR_AUTO = "auto";
    public static final String EXECUTOR_IN_PROCESS = "in-process";
    public static final String EXECUTOR_ORCHESTRATOR = "orchestrator";

    /** Default scope enumeration (spec §4.2): systems, processes, canonical-entities. */
    private List<String> scopes = List.of("systems", "processes", "canonical-entities");

    /** Targets per batch (spec §4.2 step 3). */
    private int batchSize = 20;

    /** Per-batch retry ceiling on LlmRateLimitException (spec §4.3 step 2). */
    private int maxAttempts = 3;

    /** RUNNING older than this is resumable as FAILED (spec §4.3/§4.4 stale-claim rule). */
    private int staleBatchMinutes = 30;

    /** auto | in-process | orchestrator (spec §2/§8). */
    private String executor = EXECUTOR_AUTO;

    private Dispatch dispatch = new Dispatch();

    /** true = runs execute synchronously on the caller thread (tests + the CLI runner). */
    private boolean sync = false;

    /** DiagnosticAttestationHook gate (ADR-004 D12); default false — no-op hook. */
    private boolean attestToDecisionLog = false;

    /** EXTENSION POINT(diagnostic-5) — named, default false, NOT built (spec §15.5). */
    private boolean autoAfterIngest = false;

    /** CLI runner gate (spec §10.3) — read by the app module's DiagnosticRunner. */
    private boolean run = false;

    /** CLI: resolve the run domain by name (spec §10.3). */
    private String domainName;

    /** CLI/REST default for the §10.2 zero-yield trichotomy. */
    private int requiredMinFindings = 0;

    public List<String> getScopes() { return scopes; }
    public void setScopes(List<String> scopes) { this.scopes = scopes; }

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

    public int getStaleBatchMinutes() { return staleBatchMinutes; }
    public void setStaleBatchMinutes(int staleBatchMinutes) { this.staleBatchMinutes = staleBatchMinutes; }

    public String getExecutor() { return executor; }
    public void setExecutor(String executor) { this.executor = executor; }

    public Dispatch getDispatch() { return dispatch; }
    public void setDispatch(Dispatch dispatch) { this.dispatch = dispatch; }

    public boolean isSync() { return sync; }
    public void setSync(boolean sync) { this.sync = sync; }

    public boolean isAttestToDecisionLog() { return attestToDecisionLog; }
    public void setAttestToDecisionLog(boolean attestToDecisionLog) { this.attestToDecisionLog = attestToDecisionLog; }

    public boolean isAutoAfterIngest() { return autoAfterIngest; }
    public void setAutoAfterIngest(boolean autoAfterIngest) { this.autoAfterIngest = autoAfterIngest; }

    public boolean isRun() { return run; }
    public void setRun(boolean run) { this.run = run; }

    public String getDomainName() { return domainName; }
    public void setDomainName(String domainName) { this.domainName = domainName; }

    public int getRequiredMinFindings() { return requiredMinFindings; }
    public void setRequiredMinFindings(int requiredMinFindings) { this.requiredMinFindings = requiredMinFindings; }

    /**
     * Budget-refusal / dispatch-failure fallback for the orchestrated lane
     * (spec §8.2): {@code in-process} (default) re-executes the batch on the
     * in-process executor; {@code skip} marks the batch SKIPPED_BUDGET
     * (neutral, non-error — never blocks run COMPLETED).
     */
    public static class Dispatch {
        public static final String FALLBACK_IN_PROCESS = "in-process";
        public static final String FALLBACK_SKIP = "skip";

        private String fallback = FALLBACK_IN_PROCESS;

        public String getFallback() { return fallback; }
        public void setFallback(String fallback) { this.fallback = fallback; }
    }
}
