package com.meridian.substrate.engine.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent engine configuration ({@code substrate.agents.*}) — engine-spec
 * agents.md §1. Defaults are first-engagement calibration items (ADR-003 §4
 * negative consequences).
 *
 * <p>Enforcement mode deliberately has NO key here: it rides the EXISTING
 * {@code substrate.contracts.enforcement.mode} ladder — one ladder, not two
 * (ADR-003 D7). Demo seeding rides the global {@code substrate.demo.seed}.
 */
@Component
@ConfigurationProperties(prefix = "substrate.agents")
public class AgentProperties {

    private final Mcp mcp = new Mcp();
    private final Sightings sightings = new Sightings();
    private final Liveness liveness = new Liveness();
    private final GateEval gateEval = new GateEval();

    /** Hourly dedupe bucket for agent_sightings (UNIQUE(agent, channel, bucket_key)). */
    private int sightingBucketMinutes = 60;

    /** Sweeper dwell (days) before escalating an unresolved intervention to the next oversight position. */
    private int interventionEscalationDays = 3;

    /** Sweeper dwell (hours) before flagging an unconfirmed RECORDED_INTENT control to the oversight chain. */
    private int controlConfirmTimeoutHours = 24;

    /** Renotify windowing (cascade D5 idiom). */
    private int escalationRenotifyDays = 3;

    private int escalationRenotifyLimit = 5;

    /** Hourly escalation + UNSTRUCTURED human-verify sweep (6b). */
    private String sweepCron = "0 0 * * * *";

    /** {@code substrate.agents.mcp.*} */
    public static class Mcp {
        /**
         * Registers agent tools on the existing {@code /mcp} server (6c;
         * requires the contracts MCP host — absent ⇒ tools absent, REST
         * unaffected).
         */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /** {@code substrate.agents.sightings.*} */
    public static class Sightings {
        /**
         * Reserved; no purge job in v1 (named first-engagement calibration
         * risk — EXTENSION POINT(agent-15), spec §17 #15).
         */
        private Integer retentionDays;

        public Integer getRetentionDays() {
            return retentionDays;
        }

        public void setRetentionDays(Integer retentionDays) {
            this.retentionDays = retentionDays;
        }
    }

    /** {@code substrate.agents.liveness.*} */
    public static class Liveness {
        /**
         * STALE threshold (hours) when a telemetry channel exists but
         * {@code heartbeat_expected_minutes} is unset (spec §10).
         */
        private int staleAfterHours = 72;

        public int getStaleAfterHours() {
            return staleAfterHours;
        }

        public void setStaleAfterHours(int staleAfterHours) {
            this.staleAfterHours = staleAfterHours;
        }
    }

    /** {@code substrate.agents.gate-eval.*} */
    public static class GateEval {
        /**
         * STRUCTURED criteria-gate evaluation pass (rides the contracts
         * monitor scheduler; inert without the contracts module — 6b/6c).
         */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public Mcp getMcp() {
        return mcp;
    }

    public Sightings getSightings() {
        return sightings;
    }

    public Liveness getLiveness() {
        return liveness;
    }

    public GateEval getGateEval() {
        return gateEval;
    }

    public int getSightingBucketMinutes() {
        return sightingBucketMinutes;
    }

    public void setSightingBucketMinutes(int sightingBucketMinutes) {
        this.sightingBucketMinutes = sightingBucketMinutes;
    }

    public int getInterventionEscalationDays() {
        return interventionEscalationDays;
    }

    public void setInterventionEscalationDays(int interventionEscalationDays) {
        this.interventionEscalationDays = interventionEscalationDays;
    }

    public int getControlConfirmTimeoutHours() {
        return controlConfirmTimeoutHours;
    }

    public void setControlConfirmTimeoutHours(int controlConfirmTimeoutHours) {
        this.controlConfirmTimeoutHours = controlConfirmTimeoutHours;
    }

    public int getEscalationRenotifyDays() {
        return escalationRenotifyDays;
    }

    public void setEscalationRenotifyDays(int escalationRenotifyDays) {
        this.escalationRenotifyDays = escalationRenotifyDays;
    }

    public int getEscalationRenotifyLimit() {
        return escalationRenotifyLimit;
    }

    public void setEscalationRenotifyLimit(int escalationRenotifyLimit) {
        this.escalationRenotifyLimit = escalationRenotifyLimit;
    }

    public String getSweepCron() {
        return sweepCron;
    }

    public void setSweepCron(String sweepCron) {
        this.sweepCron = sweepCron;
    }
}
