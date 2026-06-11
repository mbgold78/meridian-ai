package com.meridian.substrate.engine.contract.read;

import com.meridian.substrate.common.domain.enums.CommitmentComplianceState;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code get_obligations_for_principal} payload (engine-spec contracts.md
 * §8.2 — "the agent-ergonomics keystone"): what the calling party owes NOW —
 * open commitments, due dates, cadence, next period_key, missing attestations.
 *
 * <p>Scope: parties bound to the caller via
 * {@code contract_parties.mcp_principal_id} (pre-Phase-5: spoofable header
 * identity, matched verbatim), on ACTIVE contracts only — commitments are
 * evaluable only while the contract is ACTIVE (§2.3), so nothing is "owed"
 * under PROPOSED/DECLINED contracts. WAIVED commitments are excluded (no
 * longer owed); BREACHED ones remain listed — a breach does not discharge the
 * obligation.
 */
public record ObligationsResponse(
        String principalId,
        List<PartyObligations> parties
) {

    /** One bound party on one ACTIVE contract. */
    public record PartyObligations(
            UUID contractId,
            String contractKey,
            String contractTitle,
            UUID partyId,
            String partyLabel,
            List<Obligation> obligations
    ) {
    }

    /**
     * One owed commitment. {@code nextPeriodKey} = the period attestable now
     * (current open period for cadence-bearing commitments; {@code ONE_TIME}
     * for one-shots). {@code lastClosedPeriodKey} = the most recent closed
     * period (§5.1), null for one-shots. {@code missingAttestation} = no
     * attestation row exists for that last closed period (cadence-bearing) /
     * for {@code ONE_TIME} (one-shots) — grace arithmetic is the monitors'
     * concern (§5.3), not this read model's.
     */
    public record Obligation(
            UUID commitmentId,
            String text,
            Instant dueBy,
            String cadence,
            CommitmentComplianceState complianceState,
            String nextPeriodKey,
            String lastClosedPeriodKey,
            boolean missingAttestation
    ) {
    }
}
