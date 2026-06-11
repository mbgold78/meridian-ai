package com.meridian.substrate.engine.contract.read;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.enums.CommitmentComplianceState;
import com.meridian.substrate.common.domain.enums.ContractPartyType;
import com.meridian.substrate.common.domain.enums.ContractStatus;
import com.meridian.substrate.common.domain.enums.MonitorRunOutcome;
import com.meridian.substrate.common.domain.enums.MonitorStatus;
import com.meridian.substrate.common.domain.enums.PartyResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code GET /contracts/{idOrKey}} body (engine-spec contracts.md §7.3) —
 * a strict SUPERSET of the homepoc {@code BeaconContract} fixture shape so the
 * S5 ContractDetailView swap is an adapter, not a redesign:
 *
 * <ul>
 *   <li>{@code BeaconContract.id ← contractKey} (slug identity, ADR D8);</li>
 *   <li>{@code parties[].commitments} (string[]) {@code ← commitments[].text}
 *       in {@code sequenceOrder};</li>
 *   <li>{@code enforcement.monitor / monitorMechanism} VERBATIM text — the UI
 *       font-mono panel renders unchanged (ADR D10);</li>
 *   <li>{@code enforcement.declineTriggers} ← trigger text (with armed
 *       standing as additive structure);</li>
 *   <li>{@code linkedSubsidiarySlugs ← linkedEntities[].slug} (type
 *       SUBSIDIARY_SLUG);</li>
 *   <li>{@code estimatedValue} nullable display string — null SERIALIZES as
 *       null (vector 2 pin, §14.1);</li>
 *   <li>{@code proposedAt} — the adapter takes the date part.</li>
 * </ul>
 */
public record ContractDetailResponse(
        UUID id,
        String contractKey,
        String type,
        String title,
        String context,
        ContractStatus status,
        Instant proposedAt,
        String proposedBy,
        Instant activatedAt,
        Instant resolvedAt,
        String estimatedValue,
        BigDecimal estimatedValueAmount,
        List<Party> parties,
        Enforcement enforcement,
        List<LinkedEntityRef> linkedEntities,
        List<Monitor> monitors,
        BreachSummary breachSummary,
        JsonNode reviewBoundary
) {

    /** §7.3 {@code parties[]} element; {@code response} null until the party responds. */
    public record Party(
            UUID id,
            String partyLabel,
            ContractPartyType partyType,
            List<LinkedEntityRef> boundEntities,
            boolean responseRequired,
            Response response,
            List<Commitment> commitments
    ) {
    }

    /** Immutable party-response projection (one per party, §2.2). */
    public record Response(
            PartyResponse response,
            String respondedBy,
            Instant respondedAt,
            UUID declineTriggerId,
            String declineReasoning
    ) {
    }

    /** §7.3 commitment row in {@code sequenceOrder}. */
    public record Commitment(
            UUID id,
            String text,
            Instant dueBy,
            String cadence,
            CommitmentComplianceState complianceState,
            Instant lastEvaluatedAt
    ) {
    }

    /** §7.3 {@code enforcement} block — monitor prose VERBATIM (ADR D10). */
    public record Enforcement(
            String monitor,
            String monitorMechanism,
            List<DeclineTrigger> declineTriggers
    ) {
    }

    /**
     * Pre-agreed decline trigger with derived ARMED standing (§2.6 — armed is
     * a subtle-destructive accent, never a breach).
     */
    public record DeclineTrigger(
            UUID id,
            String text,
            boolean armed,
            UUID invocableByPartyId
    ) {
    }

    /** §7.3 {@code monitors[]} element. */
    public record Monitor(
            UUID id,
            String name,
            String ruleType,
            MonitorStatus status,
            String cadence,
            int graceDays,
            Instant lastRunAt,
            Instant nextRunAt,
            MonitorRunOutcome lastOutcome
    ) {
    }

    /** §7.3 {@code breachSummary} — open count + severity histogram. */
    public record BreachSummary(long open, Map<String, Long> bySeverity) {
    }
}
