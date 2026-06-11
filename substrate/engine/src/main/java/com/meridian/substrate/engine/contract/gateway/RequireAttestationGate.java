package com.meridian.substrate.engine.contract.gateway;

import com.meridian.substrate.common.domain.ContractCommitment;
import com.meridian.substrate.common.repo.ContractAttestationRepository;
import com.meridian.substrate.common.repo.ContractCommitmentRepository;
import com.meridian.substrate.engine.contract.ContractProperties;
import com.meridian.substrate.engine.contract.monitor.PeriodKeys;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

/**
 * The single-sourced §4.3 REQUIRE_ATTESTATION check: "current-period
 * attestation exists for {@code guard.commitment_id} ? pass :
 * deny(reason=ATTESTATION_MISSING)". Period key per §5.1 from the bound
 * commitment's cadence and the injectable engine clock/zone.
 *
 * <p>Shared by the {@code check_action} advisory ({@link AdvisoryCheckService})
 * and the real PDP ({@link ContractGateway}) so the two can never drift
 * (§14.5 verdict parity). A REQUIRE_ATTESTATION guard with no commitment
 * binding (or a dangling/cadence-less one) is unsatisfiable — reported with a
 * reason, never silently passed.
 */
@Component
public class RequireAttestationGate {

    private final ContractCommitmentRepository commitmentRepository;
    private final ContractAttestationRepository attestationRepository;
    private final ContractProperties properties;
    private final Clock clock;

    public RequireAttestationGate(ContractCommitmentRepository commitmentRepository,
                                  ContractAttestationRepository attestationRepository,
                                  ContractProperties properties,
                                  Clock clock) {
        this.commitmentRepository = commitmentRepository;
        this.attestationRepository = attestationRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Returns the missing-reason when the guard's attestation requirement is
     * unsatisfied; empty when a current-period attestation exists.
     */
    public Optional<String> missingReason(UUID guardCommitmentId) {
        if (guardCommitmentId == null) {
            return Optional.of("guard has no commitment binding to attest against");
        }
        Optional<ContractCommitment> commitment = commitmentRepository.findById(guardCommitmentId);
        if (commitment.isEmpty()) {
            return Optional.of("bound commitment " + guardCommitmentId + " not found");
        }
        String periodKey = PeriodKeys.periodKey(
                commitment.get().getCadence(), clock.instant(),
                ZoneId.of(properties.getClock().getZone()));
        if (periodKey == null) {
            return Optional.of("commitment cadence '" + commitment.get().getCadence()
                    + "' has no §5.1 period-key semantics");
        }
        boolean attested = attestationRepository
                .findByCommitmentIdAndPeriodKey(guardCommitmentId, periodKey)
                .isPresent();
        return attested ? Optional.empty()
                : Optional.of("no attestation for period " + periodKey);
    }
}
