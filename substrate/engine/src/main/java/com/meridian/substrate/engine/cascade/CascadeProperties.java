package com.meridian.substrate.engine.cascade;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Cascade engine configuration ({@code substrate.cascade.*}) — engine-spec
 * cascade.md §9; ADR-001 D5. Defaults are first-engagement calibration items
 * (ADR §4 negative consequences).
 */
@Component
@ConfigurationProperties(prefix = "substrate.cascade")
public class CascadeProperties {

    /**
     * Engagement default response SLA, applied (and stored on the cascade row)
     * at issue time when the draft carries no explicit {@code responseSlaDays}.
     * Explicit {@code 0} disables the SLA per cascade ({@code response_deadline}
     * NULL).
     */
    private int defaultResponseSlaDays = 7;

    /** Re-notify window length in days for overdue notifications (sweeper, §9). */
    private int overdueRenotifyDays = 3;

    /** Maximum number of re-notify windows (n = 0..limit) per level/round. */
    private int overdueRenotifyLimit = 5;

    /** Hourly by default; consumed by {@code CascadeEscalationSweeper} (§9). */
    private String sweepCron = "0 0 * * * *";

    /**
     * EXTENSION POINT(cascade-10): decision-log attestation — reserved flag,
     * spec §13.10 / ADR D15. The digest-anchoring implementation behind
     * {@link CascadeAttestationHook} is NOT built in Phase 2.
     */
    private boolean attestToDecisionLog = false;

    public int getDefaultResponseSlaDays() {
        return defaultResponseSlaDays;
    }

    public void setDefaultResponseSlaDays(int defaultResponseSlaDays) {
        this.defaultResponseSlaDays = defaultResponseSlaDays;
    }

    public int getOverdueRenotifyDays() {
        return overdueRenotifyDays;
    }

    public void setOverdueRenotifyDays(int overdueRenotifyDays) {
        this.overdueRenotifyDays = overdueRenotifyDays;
    }

    public int getOverdueRenotifyLimit() {
        return overdueRenotifyLimit;
    }

    public void setOverdueRenotifyLimit(int overdueRenotifyLimit) {
        this.overdueRenotifyLimit = overdueRenotifyLimit;
    }

    public String getSweepCron() {
        return sweepCron;
    }

    public void setSweepCron(String sweepCron) {
        this.sweepCron = sweepCron;
    }

    public boolean isAttestToDecisionLog() {
        return attestToDecisionLog;
    }

    public void setAttestToDecisionLog(boolean attestToDecisionLog) {
        this.attestToDecisionLog = attestToDecisionLog;
    }
}
