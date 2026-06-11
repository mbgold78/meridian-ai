package com.meridian.substrate.engine.contract.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractAttestation;
import com.meridian.substrate.common.domain.ContractClauseGuard;
import com.meridian.substrate.common.domain.ContractCommitment;
import com.meridian.substrate.common.domain.ContractMetricSample;
import com.meridian.substrate.common.domain.enums.GuardEffect;
import com.meridian.substrate.common.repo.ContractAttestationRepository;
import com.meridian.substrate.common.repo.ContractClauseGuardRepository;
import com.meridian.substrate.common.repo.ContractCommitmentRepository;
import com.meridian.substrate.common.repo.ContractMetricSampleRepository;
import com.meridian.substrate.common.repo.ContractRepository;
import com.meridian.substrate.engine.contract.ContractProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code check_action} advisory semantics (engine-spec contracts.md §8.2 over
 * the §4.3 algorithm): effect precedence, REQUIRE_ATTESTATION with/without a
 * current-period attestation, entity-type narrowing, §4.4 INDETERMINATE
 * flagging (never silent), metric-leaf resolution, and the structural
 * "never rows a decision" guarantee.
 *
 * <p>Fixed clock 2026-06-10T12:00Z (current MONTHLY period {@code 2026-06}).
 */
class AdvisoryCheckServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-10T12:00:00Z");

    private final ContractClauseGuardRepository guardRepo = mock(ContractClauseGuardRepository.class);
    private final ContractRepository contractRepo = mock(ContractRepository.class);
    private final ContractCommitmentRepository commitmentRepo = mock(ContractCommitmentRepository.class);
    private final ContractAttestationRepository attestationRepo = mock(ContractAttestationRepository.class);
    private final ContractMetricSampleRepository metricRepo = mock(ContractMetricSampleRepository.class);
    private final ObjectMapper json = new ObjectMapper();

    private final UUID contractId = UUID.randomUUID();
    private AdvisoryCheckService service;

    @BeforeEach
    void setUp() {
        ContractProperties properties = new ContractProperties();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        // The shared single-sourced pieces the real ContractGateway also uses
        // (§14.5 parity is structural — same gate, same metric leaf).
        service = new AdvisoryCheckService(guardRepo, contractRepo,
                new RequireAttestationGate(commitmentRepo, attestationRepo, properties, clock),
                new ContractMetricLeafResolver(metricRepo, properties, clock), json);
        Contract contract = new Contract();
        contract.setId(contractId);
        contract.setContractKey("synthetic-guard-bearing");
        when(contractRepo.findById(contractId)).thenReturn(Optional.of(contract));
        when(attestationRepo.findByCommitmentIdAndPeriodKey(any(), any()))
                .thenReturn(Optional.empty());
    }

    private ContractClauseGuard guard(GuardEffect effect, String entityType, String predicate) {
        ContractClauseGuard g = new ContractClauseGuard();
        g.setId(UUID.randomUUID());
        g.setContractId(contractId);
        g.setActionType("agent.promote");
        g.setEntityType(entityType);
        g.setEffect(effect);
        g.setActive(true);
        g.setDescription(effect + " guard");
        try {
            g.setPredicate(predicate == null ? null : json.readTree(predicate));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return g;
    }

    private void activeGuards(ContractClauseGuard... guards) {
        when(guardRepo.findByActionTypeAndActiveTrue("agent.promote"))
                .thenReturn(List.of(guards));
    }

    // ----------------------------------------------------------------- verdicts

    @Test
    void noGuardsIsPlainAllow_andNeverRowsADecision() {
        activeGuards();
        long before = service.advisoryCheckCount();
        var result = service.check("agent.promote", null, null, null);
        assertThat(result.verdict()).isEqualTo("ALLOW");
        assertThat(result.matchedClauses()).isEmpty();
        assertThat(result.explanation()).contains("No active clause guards");
        // §8.2 "metrics counter only": the counter moved; the service has no
        // ContractEnforcementDecisionRepository dependency AT ALL (structural
        // never-rows guarantee — see its constructor).
        assertThat(service.advisoryCheckCount()).isEqualTo(before + 1);
    }

    @Test
    void matchedDenyGuardBlocks() {
        activeGuards(guard(GuardEffect.DENY, null, null));
        var result = service.check("agent.promote", null, null, null);
        assertThat(result.verdict()).isEqualTo("BLOCK");
        assertThat(result.matchedClauses()).hasSize(1);
        assertThat(result.matchedClauses().get(0).effect()).isEqualTo(GuardEffect.DENY);
        assertThat(result.matchedClauses().get(0).contractKey())
                .isEqualTo("synthetic-guard-bearing");
        assertThat(result.explanation()).contains("WOULD_DENY");
    }

    @Test
    void effectPrecedenceDenyOverFlag() {
        activeGuards(guard(GuardEffect.FLAG, null, null), guard(GuardEffect.DENY, null, null));
        var result = service.check("agent.promote", null, null, null);
        assertThat(result.verdict()).isEqualTo("BLOCK");
        // DENY > REQUIRE_ATTESTATION > FLAG ordering in the citation list (§4.3).
        assertThat(result.matchedClauses().get(0).effect()).isEqualTo(GuardEffect.DENY);
        assertThat(result.matchedClauses().get(1).effect()).isEqualTo(GuardEffect.FLAG);
    }

    @Test
    void flagGuardIsAllowWithFlag() {
        activeGuards(guard(GuardEffect.FLAG, null, null));
        var result = service.check("agent.promote", null, null, null);
        assertThat(result.verdict()).isEqualTo("ALLOW_WITH_FLAG");
        assertThat(result.matchedClauses()).hasSize(1);
    }

    @Test
    void predicateNoMatchIsPlainAllowWithExplanation() {
        activeGuards(guard(GuardEffect.DENY, null,
                "{\"field\":\"payload.scope\",\"eq\":\"other\"}"));
        var result = service.check("agent.promote", null, null,
                json.createObjectNode().put("scope", "hipaa-attestation-forms"));
        assertThat(result.verdict()).isEqualTo("ALLOW");
        assertThat(result.matchedClauses()).isEmpty();
        assertThat(result.explanation()).contains("none matched");
    }

    @Test
    void predicateMatchAgainstPayloadDotPath() {
        activeGuards(guard(GuardEffect.DENY, null,
                "{\"all\":[{\"field\":\"payload.scope\",\"eq\":\"hipaa-attestation-forms\"},"
                        + "{\"field\":\"payload.formCount\",\"lte\":40}]}"));
        var result = service.check("agent.promote", null, null,
                json.createObjectNode().put("scope", "hipaa-attestation-forms").put("formCount", 38));
        assertThat(result.verdict()).isEqualTo("BLOCK");
    }

    // ------------------------------------------------------ REQUIRE_ATTESTATION

    @Test
    void requireAttestationWithoutCurrentPeriodAttestationBlocks() {
        UUID commitmentId = UUID.randomUUID();
        ContractCommitment commitment = new ContractCommitment();
        commitment.setId(commitmentId);
        commitment.setCadence("MONTHLY");
        when(commitmentRepo.findById(commitmentId)).thenReturn(Optional.of(commitment));
        ContractClauseGuard g = guard(GuardEffect.REQUIRE_ATTESTATION, null, null);
        g.setCommitmentId(commitmentId);
        activeGuards(g);

        var result = service.check("agent.promote", null, null, null);
        assertThat(result.verdict()).isEqualTo("BLOCK");
        assertThat(result.explanation()).contains("ATTESTATION_MISSING").contains("2026-06");
    }

    @Test
    void requireAttestationWithCurrentPeriodAttestationPasses() {
        UUID commitmentId = UUID.randomUUID();
        ContractCommitment commitment = new ContractCommitment();
        commitment.setId(commitmentId);
        commitment.setCadence("MONTHLY");
        when(commitmentRepo.findById(commitmentId)).thenReturn(Optional.of(commitment));
        when(attestationRepo.findByCommitmentIdAndPeriodKey(commitmentId, "2026-06"))
                .thenReturn(Optional.of(new ContractAttestation()));
        ContractClauseGuard g = guard(GuardEffect.REQUIRE_ATTESTATION, null, null);
        g.setCommitmentId(commitmentId);
        activeGuards(g);

        var result = service.check("agent.promote", null, null, null);
        assertThat(result.verdict()).isEqualTo("ALLOW");
        assertThat(result.explanation()).contains("satisfied");
    }

    @Test
    void requireAttestationWithoutCommitmentBindingIsReportedNotSilentlyPassed() {
        activeGuards(guard(GuardEffect.REQUIRE_ATTESTATION, null, null));
        var result = service.check("agent.promote", null, null, null);
        assertThat(result.verdict()).isEqualTo("BLOCK");
        assertThat(result.explanation()).contains("no commitment binding");
    }

    // --------------------------------------------------------------- narrowing

    @Test
    void entityTypeNarrowing() {
        activeGuards(guard(GuardEffect.DENY, "PROCESS", null));
        assertThat(service.check("agent.promote", "SYSTEM", "s-1", null).verdict())
                .isEqualTo("ALLOW");
        assertThat(service.check("agent.promote", "PROCESS", "p-1", null).verdict())
                .isEqualTo("BLOCK");
        // null guard entity_type = wildcard
        activeGuards(guard(GuardEffect.DENY, null, null));
        assertThat(service.check("agent.promote", "SYSTEM", "s-1", null).verdict())
                .isEqualTo("BLOCK");
    }

    // ----------------------------------------------------- never-silent (§4.4)

    @Test
    void indeterminatePredicateIsSkippedButFlagged() {
        activeGuards(guard(GuardEffect.DENY, null,
                "{\"sustainedFor\":{\"quarters\":2}}"));   // beyond-v1 node (ADR D9)
        var result = service.check("agent.promote", null, null, null);
        assertThat(result.verdict()).isEqualTo("ALLOW_WITH_FLAG");  // never a silent ALLOW
        assertThat(result.matchedClauses()).isEmpty();
        assertThat(result.explanation()).contains("INDETERMINATE");
    }

    // ------------------------------------------------------------- metric leaf

    @Test
    void metricLeafResolvesLatestSingleClosedPeriodSample() {
        ContractMetricSample may = sample("accuracy", 0.88, "2026-05");
        ContractMetricSample april = sample("accuracy", 0.95, "2026-04");
        when(metricRepo.findByContractIdAndMetricKey(eq(contractId), eq("accuracy")))
                .thenReturn(List.of(april, may));
        activeGuards(guard(GuardEffect.DENY, null, "{\"metric\":\"accuracy\",\"lt\":0.90}"));

        // Latest CLOSED period (2026-05; 2026-06 is still open at NOW) wins → 0.88 < 0.90.
        assertThat(service.check("agent.promote", null, null, null).verdict())
                .isEqualTo("BLOCK");
    }

    @Test
    void metricLeafWithMultiPartySamplesIsIndeterminateNotAggregated() {
        // Flag-not-invent: two parties reported for the latest closed period —
        // no aggregation rule is documented, so the leaf must NOT average.
        when(metricRepo.findByContractIdAndMetricKey(eq(contractId), eq("accuracy")))
                .thenReturn(List.of(sample("accuracy", 0.80, "2026-05"),
                        sample("accuracy", 0.99, "2026-05")));
        activeGuards(guard(GuardEffect.DENY, null, "{\"metric\":\"accuracy\",\"lt\":0.90}"));

        var result = service.check("agent.promote", null, null, null);
        assertThat(result.verdict()).isEqualTo("ALLOW_WITH_FLAG");
        assertThat(result.explanation()).contains("INDETERMINATE");
    }

    @Test
    void actionTypeIsRequired() {
        assertThatThrownBy(() -> service.check("  ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ContractMetricSample sample(String key, double value, String periodKey) {
        ContractMetricSample s = new ContractMetricSample();
        s.setId(UUID.randomUUID());
        s.setContractId(contractId);
        s.setPartyId(UUID.randomUUID());
        s.setMetricKey(key);
        s.setMetricValue(value);
        s.setPeriodKey(periodKey);
        return s;
    }
}
