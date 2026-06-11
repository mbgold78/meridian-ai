package com.meridian.substrate.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code substrate.stats.*} configuration (engine-spec diagnostic.md §2/§12)
 * — D5 computed-statistics knobs. Both values are first-engagement
 * calibration items, not invariants (ADR-004 D13 REVISIT trigger).
 *
 * <ul>
 *   <li>{@code stall-threshold-days} — the §12.1 lifecycle-stall dwell
 *       threshold. The fixture's "90+ days" one-liner becomes this config
 *       knob.</li>
 *   <li>{@code pattern-window-days} — DOCUMENTATION of the §12.2 trailing
 *       window, which is DELEGATED to the contract engine
 *       ({@code ContractSignalService.COORDINATION_WINDOW} owns it; spec §2:
 *       "the window stays theirs"). This key is never wired into a
 *       portfolio-side computation — a fork would drift against the owning
 *       engine. {@code PortfolioStatsUnitTest} pins the two values equal so
 *       divergence is a reviewed change, not silent.</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "substrate.stats")
public class StatsProperties {

    /** §12.1 stall threshold (days); default 90 — config knob, not invariant. */
    private int stallThresholdDays = 90;

    /** §12.2 window documentation — owned by contracts §11.2, never forked here. */
    private int patternWindowDays = 7;

    public int getStallThresholdDays() { return stallThresholdDays; }
    public void setStallThresholdDays(int stallThresholdDays) { this.stallThresholdDays = stallThresholdDays; }

    public int getPatternWindowDays() { return patternWindowDays; }
    public void setPatternWindowDays(int patternWindowDays) { this.patternWindowDays = patternWindowDays; }
}
