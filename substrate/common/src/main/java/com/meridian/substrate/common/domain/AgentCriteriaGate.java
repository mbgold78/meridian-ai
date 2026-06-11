package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.enums.AgentGateRuleType;
import com.meridian.substrate.common.domain.enums.AgentGateStanding;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * deploymentCriteria as a runtime gate (engine-spec agents.md §4 table 6 / §9;
 * ADR-003 D9). One mandate clause per row, authored at deploy ONLY and frozen
 * with the mandate (invariant I2): gate text, rule type and predicate never
 * change post-deploy — only {@code standing}/{@code lastEvaluatedAt} (the
 * denormalized standing, contracts §2.6 arming idiom).
 *
 * <p>STRUCTURED rows carry the contracts TinyDsl predicate VERBATIM (contracts
 * spec §4.4) — no second DSL exists in {@code engine.agent} (ArchUnit-asserted
 * at 6b/6c); metric leaves resolve against {@code agent_metric_samples}.
 * UNSTRUCTURED prose ("zero PHI leakage") has predicate NULL — flagged not
 * faked. Evaluation (6b/6c) is signal-only: never auto-pause, never auto-kill.
 */
@Entity
@Table(name = "agent_criteria_gates",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_acg_order",
                        columnNames = {"agent_registration_id", "sequence_order"})
        })
public class AgentCriteriaGate extends BaseEntity {

    /** Soft ref → agent_registrations; no DB FK. */
    @Column(name = "agent_registration_id", nullable = false)
    private UUID agentRegistrationId;

    @Column(name = "sequence_order", nullable = false)
    private int sequenceOrder;

    /** VERBATIM mandate clause ('target 90% pre-fill accuracy'). */
    @Column(name = "gate_text", nullable = false, columnDefinition = "TEXT")
    private String gateText;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 20)
    private AgentGateRuleType ruleType;

    /** JSON contracts-TinyDsl predicate; NULL iff UNSTRUCTURED. */
    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "predicate", columnDefinition = "TEXT")
    private JsonNode predicate;

    /** MONTHLY | QUARTERLY | WEEKLY | DAILY (validated string). */
    @Column(name = "cadence", length = 16)
    private String cadence;

    @Enumerated(EnumType.STRING)
    @Column(name = "standing", nullable = false, length = 24)
    private AgentGateStanding standing = AgentGateStanding.UNEVALUATED;

    @Column(name = "last_evaluated_at")
    private Instant lastEvaluatedAt;

    public UUID getAgentRegistrationId() {
        return agentRegistrationId;
    }

    public void setAgentRegistrationId(UUID agentRegistrationId) {
        this.agentRegistrationId = agentRegistrationId;
    }

    public int getSequenceOrder() {
        return sequenceOrder;
    }

    public void setSequenceOrder(int sequenceOrder) {
        this.sequenceOrder = sequenceOrder;
    }

    public String getGateText() {
        return gateText;
    }

    public void setGateText(String gateText) {
        this.gateText = gateText;
    }

    public AgentGateRuleType getRuleType() {
        return ruleType;
    }

    public void setRuleType(AgentGateRuleType ruleType) {
        this.ruleType = ruleType;
    }

    public JsonNode getPredicate() {
        return predicate;
    }

    public void setPredicate(JsonNode predicate) {
        this.predicate = predicate;
    }

    public String getCadence() {
        return cadence;
    }

    public void setCadence(String cadence) {
        this.cadence = cadence;
    }

    public AgentGateStanding getStanding() {
        return standing;
    }

    public void setStanding(AgentGateStanding standing) {
        this.standing = standing;
    }

    public Instant getLastEvaluatedAt() {
        return lastEvaluatedAt;
    }

    public void setLastEvaluatedAt(Instant lastEvaluatedAt) {
        this.lastEvaluatedAt = lastEvaluatedAt;
    }
}
