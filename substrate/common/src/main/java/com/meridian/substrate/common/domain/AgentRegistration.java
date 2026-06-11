package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.enums.AgentExecutionLocus;
import com.meridian.substrate.common.domain.enums.AgentOperationalMode;
import com.meridian.substrate.common.domain.enums.AgentReadinessSource;
import com.meridian.substrate.common.domain.enums.AgentRetiredReason;
import com.meridian.substrate.common.domain.enums.AgentSightingChannel;
import com.meridian.substrate.common.domain.enums.AgentSupervisoryModel;
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
 * Agent registry aggregate root (engine-spec agents.md §4 table 1; ADR-003
 * D1/D3). A registration is born ONLY at the deploy act with the
 * [AGENT-PROVENANCE] facts as NOT NULL columns — a provenance-less agent row
 * cannot exist (invariant I1).
 *
 * <p>The mandate is IMMUTABLE post-deploy (ADR D5 / invariant I2): no code
 * path updates {@code deploymentCriteria}, {@code criteriaDigest} or the
 * deployed-* provenance after the DEPLOYED event; change = retire + redeploy
 * with {@code predecessorRegistrationId} lineage.
 *
 * <p>Lifecycle / control-confirmation / liveness blocks are rebuildable
 * PROJECTIONS (⟲) of the append-only {@code agent_status_events} log, updated
 * synchronously in the same transaction (invariant I9/I10); liveness fields
 * change ONLY from sighting writes, never row recency (invariant I7).
 *
 * <p>All references are soft (no DB FKs, app-enforced — canonical-model
 * convention): domain, capability, member, process, initiative, predecessor.
 * No Lombok on entities; JSON columns via {@link JsonNodeConverter} → TEXT
 * (port-map §4.5 — jsonb flip is a documented later one-pass migration).
 *
 * <p>EXTENSION POINT(agent-11): agent-as-cascade-level-owner (cascade §13.7)
 * — this registry supplies the stable UUID + slug + label idiom a future
 * nullable {@code owner_agent_registration_id} on cascade tables would ride;
 * NO cascade column lands in V6 (queue-#24 session + first engagement wanting
 * an agent accountable inside a chain).
 */
@Entity
@Table(name = "agent_registrations",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_agent_domain_key", columnNames = {"domain_id", "agent_key"})
        })
public class AgentRegistration extends BaseEntity {

    /** Soft ref → domains; a Beacon subsidiary IS a Domain (cascade D13). */
    @Column(name = "domain_id", nullable = false)
    private UUID domainId;

    /** Slug identity ('a-pre-visit'); contract_key idiom (ADR-002 D8). */
    @Column(name = "agent_key", nullable = false, length = 160)
    private String agentKey;

    @Column(name = "name", nullable = false)
    private String name;

    /** Fixture {@code role}. */
    @Column(name = "role_description", nullable = false, length = 512)
    private String roleDescription;

    /** Soft ref → capabilities (fixture capabilityId); no DB FK. */
    @Column(name = "capability_id")
    private UUID capabilityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_locus", nullable = false, length = 30)
    private AgentExecutionLocus executionLocus;

    /** Binds Principal(AGENT,…); contract_parties idiom; Phase 5 OIDC hardens (no schema change). */
    @Column(name = "mcp_principal_id")
    private String mcpPrincipalId;

    // ------------------------------------------------------------ provenance
    // REQUIRED at deploy ([AGENT-PROVENANCE]); immutable except oversight links

    /** Soft ref → organization_members (cascade D11 owner idiom); no DB FK. */
    @Column(name = "deployed_by_member_id")
    private UUID deployedByMemberId;

    @Column(name = "deployed_by_label", nullable = false)
    private String deployedByLabel;

    /** Server clock at the deploy act (injectable, S15). */
    @Column(name = "deployed_at", nullable = false)
    private Instant deployedAt;

    /** VERBATIM prose mandate; IMMUTABLE post-deploy (ADR D5). */
    @Column(name = "deployment_criteria", nullable = false, columnDefinition = "TEXT")
    private String deploymentCriteria;

    /** SHA-256(prose + ordered gates); anchored in the DEPLOYED chain entry (6b). */
    @Column(name = "criteria_digest", nullable = false, length = 64)
    private String criteriaDigest;

    /** Retire+redeploy lineage (soft ref → agent_registrations; no DB FK). */
    @Column(name = "predecessor_registration_id")
    private UUID predecessorRegistrationId;

    // ------------------------- AgentDeploymentSpec unification ([AGENT-SPEC-UNIFICATION], doc 06)

    /** Soft ref → processes (nullable: fixtures bind capability, not process). */
    @Column(name = "target_process_id")
    private UUID targetProcessId;

    /** JSON array of ProcessStep UUIDs. */
    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "target_step_ids", columnDefinition = "TEXT")
    private JsonNode targetStepIds;

    /** 0..100 (DB CHECK); nullable. */
    @Column(name = "agent_readiness_score")
    private Integer agentReadinessScore;

    /** Service rule: NOT NULL iff {@code agentReadinessScore} present (P7 discipline, ADR D3). */
    @Enumerated(EnumType.STRING)
    @Column(name = "readiness_source", length = 32)
    private AgentReadinessSource readinessSource;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "required_system_access", columnDefinition = "TEXT")
    private JsonNode requiredSystemAccess;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "required_data_access", columnDefinition = "TEXT")
    private JsonNode requiredDataAccess;

    /** API-required (unification posture, NOT provenance — ADR D3); record/display/publish only. */
    @Enumerated(EnumType.STRING)
    @Column(name = "supervisory_model", nullable = false, length = 40)
    private AgentSupervisoryModel supervisoryModel;

    /** Reserved soft ref; the plan engine does not exist. EXTENSION POINT(agent-9). */
    @Column(name = "initiative_id")
    private UUID initiativeId;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    // ------------------------------------------- P11 safety defaults as data

    @Column(name = "read_only", nullable = false)
    private boolean readOnly = true;

    /** JSON, nullable. Evaluator is 6c ACTIONS_PER_WINDOW only; EXTENSION POINT(agent-10). */
    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "schedule_window", columnDefinition = "TEXT")
    private JsonNode scheduleWindow;

    /** 0..100 (DB CHECK); nullable. */
    @Column(name = "sampling_pct")
    private Integer samplingPct;

    // ------------------------------------------------- lifecycle projections ⟲

    @Enumerated(EnumType.STRING)
    @Column(name = "operational_mode", nullable = false, length = 20)
    private AgentOperationalMode operationalMode;

    /** Resume returns HERE (M7). */
    @Enumerated(EnumType.STRING)
    @Column(name = "mode_before_pause", length = 20)
    private AgentOperationalMode modeBeforePause;

    @Column(name = "open_intervention_count", nullable = false)
    private int openInterventionCount = 0;

    /** Fencing token, +1 per event (cascade D8); equals max(event event_seq) — invariant I10. */
    @Column(name = "event_seq", nullable = false)
    private int eventSeq = 0;

    /** Renders "shadow-mode N weeks"; reset on DEMOTED_TO_SHADOW. */
    @Column(name = "shadow_entered_at")
    private Instant shadowEnteredAt;

    @Column(name = "promotion_requested_at")
    private Instant promotionRequestedAt;

    @Column(name = "retired_at")
    private Instant retiredAt;

    /** KILLED | RETIRED (Mike-flagged distinction, ADR D4). */
    @Enumerated(EnumType.STRING)
    @Column(name = "retired_reason", length = 20)
    private AgentRetiredReason retiredReason;

    @Column(name = "retired_by_label")
    private String retiredByLabel;

    // -------------------------------------- control-confirmation projections ⟲
    // (RECORDED_INTENT tracking — ADR D6)

    /** pause | kill | retire | throttle … NULL = none pending. */
    @Column(name = "control_pending_verb", length = 20)
    private String controlPendingVerb;

    @Column(name = "control_requested_at")
    private Instant controlRequestedAt;

    /** Runtime saw the order (MCP acknowledge_control, M17). */
    @Column(name = "control_acknowledged_at")
    private Instant controlAcknowledgedAt;

    /** Human attested it stopped (REST confirm-control, M16). */
    @Column(name = "control_confirmed_at")
    private Instant controlConfirmedAt;

    @Column(name = "control_confirmed_by_label")
    private String controlConfirmedByLabel;

    // ------------------------------------------------- liveness projections ⟲
    // (from sightings ONLY — never row recency; invariant I7)

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_seen_via", length = 30)
    private AgentSightingChannel lastSeenVia;

    @Column(name = "last_attested_at")
    private Instant lastAttestedAt;

    @Column(name = "last_attested_by_label")
    private String lastAttestedByLabel;

    /** NULL = "not expected to report" (never stale-red — ADR D11). */
    @Column(name = "heartbeat_expected_minutes")
    private Integer heartbeatExpectedMinutes;

    // -------------------------------------------------------------- accessors

    public UUID getDomainId() {
        return domainId;
    }

    public void setDomainId(UUID domainId) {
        this.domainId = domainId;
    }

    public String getAgentKey() {
        return agentKey;
    }

    public void setAgentKey(String agentKey) {
        this.agentKey = agentKey;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRoleDescription() {
        return roleDescription;
    }

    public void setRoleDescription(String roleDescription) {
        this.roleDescription = roleDescription;
    }

    public UUID getCapabilityId() {
        return capabilityId;
    }

    public void setCapabilityId(UUID capabilityId) {
        this.capabilityId = capabilityId;
    }

    public AgentExecutionLocus getExecutionLocus() {
        return executionLocus;
    }

    public void setExecutionLocus(AgentExecutionLocus executionLocus) {
        this.executionLocus = executionLocus;
    }

    public String getMcpPrincipalId() {
        return mcpPrincipalId;
    }

    public void setMcpPrincipalId(String mcpPrincipalId) {
        this.mcpPrincipalId = mcpPrincipalId;
    }

    public UUID getDeployedByMemberId() {
        return deployedByMemberId;
    }

    public void setDeployedByMemberId(UUID deployedByMemberId) {
        this.deployedByMemberId = deployedByMemberId;
    }

    public String getDeployedByLabel() {
        return deployedByLabel;
    }

    public void setDeployedByLabel(String deployedByLabel) {
        this.deployedByLabel = deployedByLabel;
    }

    public Instant getDeployedAt() {
        return deployedAt;
    }

    public void setDeployedAt(Instant deployedAt) {
        this.deployedAt = deployedAt;
    }

    public String getDeploymentCriteria() {
        return deploymentCriteria;
    }

    public void setDeploymentCriteria(String deploymentCriteria) {
        this.deploymentCriteria = deploymentCriteria;
    }

    public String getCriteriaDigest() {
        return criteriaDigest;
    }

    public void setCriteriaDigest(String criteriaDigest) {
        this.criteriaDigest = criteriaDigest;
    }

    public UUID getPredecessorRegistrationId() {
        return predecessorRegistrationId;
    }

    public void setPredecessorRegistrationId(UUID predecessorRegistrationId) {
        this.predecessorRegistrationId = predecessorRegistrationId;
    }

    public UUID getTargetProcessId() {
        return targetProcessId;
    }

    public void setTargetProcessId(UUID targetProcessId) {
        this.targetProcessId = targetProcessId;
    }

    public JsonNode getTargetStepIds() {
        return targetStepIds;
    }

    public void setTargetStepIds(JsonNode targetStepIds) {
        this.targetStepIds = targetStepIds;
    }

    public Integer getAgentReadinessScore() {
        return agentReadinessScore;
    }

    public void setAgentReadinessScore(Integer agentReadinessScore) {
        this.agentReadinessScore = agentReadinessScore;
    }

    public AgentReadinessSource getReadinessSource() {
        return readinessSource;
    }

    public void setReadinessSource(AgentReadinessSource readinessSource) {
        this.readinessSource = readinessSource;
    }

    public JsonNode getRequiredSystemAccess() {
        return requiredSystemAccess;
    }

    public void setRequiredSystemAccess(JsonNode requiredSystemAccess) {
        this.requiredSystemAccess = requiredSystemAccess;
    }

    public JsonNode getRequiredDataAccess() {
        return requiredDataAccess;
    }

    public void setRequiredDataAccess(JsonNode requiredDataAccess) {
        this.requiredDataAccess = requiredDataAccess;
    }

    public AgentSupervisoryModel getSupervisoryModel() {
        return supervisoryModel;
    }

    public void setSupervisoryModel(AgentSupervisoryModel supervisoryModel) {
        this.supervisoryModel = supervisoryModel;
    }

    public UUID getInitiativeId() {
        return initiativeId;
    }

    public void setInitiativeId(UUID initiativeId) {
        this.initiativeId = initiativeId;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public JsonNode getScheduleWindow() {
        return scheduleWindow;
    }

    public void setScheduleWindow(JsonNode scheduleWindow) {
        this.scheduleWindow = scheduleWindow;
    }

    public Integer getSamplingPct() {
        return samplingPct;
    }

    public void setSamplingPct(Integer samplingPct) {
        this.samplingPct = samplingPct;
    }

    public AgentOperationalMode getOperationalMode() {
        return operationalMode;
    }

    public void setOperationalMode(AgentOperationalMode operationalMode) {
        this.operationalMode = operationalMode;
    }

    public AgentOperationalMode getModeBeforePause() {
        return modeBeforePause;
    }

    public void setModeBeforePause(AgentOperationalMode modeBeforePause) {
        this.modeBeforePause = modeBeforePause;
    }

    public int getOpenInterventionCount() {
        return openInterventionCount;
    }

    public void setOpenInterventionCount(int openInterventionCount) {
        this.openInterventionCount = openInterventionCount;
    }

    public int getEventSeq() {
        return eventSeq;
    }

    public void setEventSeq(int eventSeq) {
        this.eventSeq = eventSeq;
    }

    public Instant getShadowEnteredAt() {
        return shadowEnteredAt;
    }

    public void setShadowEnteredAt(Instant shadowEnteredAt) {
        this.shadowEnteredAt = shadowEnteredAt;
    }

    public Instant getPromotionRequestedAt() {
        return promotionRequestedAt;
    }

    public void setPromotionRequestedAt(Instant promotionRequestedAt) {
        this.promotionRequestedAt = promotionRequestedAt;
    }

    public Instant getRetiredAt() {
        return retiredAt;
    }

    public void setRetiredAt(Instant retiredAt) {
        this.retiredAt = retiredAt;
    }

    public AgentRetiredReason getRetiredReason() {
        return retiredReason;
    }

    public void setRetiredReason(AgentRetiredReason retiredReason) {
        this.retiredReason = retiredReason;
    }

    public String getRetiredByLabel() {
        return retiredByLabel;
    }

    public void setRetiredByLabel(String retiredByLabel) {
        this.retiredByLabel = retiredByLabel;
    }

    public String getControlPendingVerb() {
        return controlPendingVerb;
    }

    public void setControlPendingVerb(String controlPendingVerb) {
        this.controlPendingVerb = controlPendingVerb;
    }

    public Instant getControlRequestedAt() {
        return controlRequestedAt;
    }

    public void setControlRequestedAt(Instant controlRequestedAt) {
        this.controlRequestedAt = controlRequestedAt;
    }

    public Instant getControlAcknowledgedAt() {
        return controlAcknowledgedAt;
    }

    public void setControlAcknowledgedAt(Instant controlAcknowledgedAt) {
        this.controlAcknowledgedAt = controlAcknowledgedAt;
    }

    public Instant getControlConfirmedAt() {
        return controlConfirmedAt;
    }

    public void setControlConfirmedAt(Instant controlConfirmedAt) {
        this.controlConfirmedAt = controlConfirmedAt;
    }

    public String getControlConfirmedByLabel() {
        return controlConfirmedByLabel;
    }

    public void setControlConfirmedByLabel(String controlConfirmedByLabel) {
        this.controlConfirmedByLabel = controlConfirmedByLabel;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public AgentSightingChannel getLastSeenVia() {
        return lastSeenVia;
    }

    public void setLastSeenVia(AgentSightingChannel lastSeenVia) {
        this.lastSeenVia = lastSeenVia;
    }

    public Instant getLastAttestedAt() {
        return lastAttestedAt;
    }

    public void setLastAttestedAt(Instant lastAttestedAt) {
        this.lastAttestedAt = lastAttestedAt;
    }

    public String getLastAttestedByLabel() {
        return lastAttestedByLabel;
    }

    public void setLastAttestedByLabel(String lastAttestedByLabel) {
        this.lastAttestedByLabel = lastAttestedByLabel;
    }

    public Integer getHeartbeatExpectedMinutes() {
        return heartbeatExpectedMinutes;
    }

    public void setHeartbeatExpectedMinutes(Integer heartbeatExpectedMinutes) {
        this.heartbeatExpectedMinutes = heartbeatExpectedMinutes;
    }
}
