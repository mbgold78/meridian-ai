package com.meridian.substrate.engine.contract.monitor;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractMetricSample;
import com.meridian.substrate.common.domain.ContractMonitor;
import com.meridian.substrate.common.domain.ContractParty;
import com.meridian.substrate.common.domain.enums.MonitorRuleType;
import com.meridian.substrate.common.domain.enums.MonitorRunOutcome;
import com.meridian.substrate.common.domain.enums.MonitorStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §5.3 METRIC_THRESHOLD matrix (engine-spec contracts.md §14.3):
 * complete/partial/over/under — NEVER computes on partial data (ADR D11);
 * zero-yield surfaces as INDETERMINATE with the missing parties in the trace
 * (the §13 3b "zero-yield surfaces, never silent" hard requirement).
 *
 * <p>Fixture-derived constants (metricKey utilization_share, threshold 0.30,
 * QUARTERLY, two required parties) follow the compiled fi-shared-pre-fill-agent
 * monitor. Source of truth:
 * C:/Users/mbgol/dev/meridian-homepoc/frontend/src/demo/beacon/contracts.ts
 * (copied 2026-06-11).
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:contract-metric-eval;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MetricThresholdEvaluatorTest {

    /** Q2 2026 closed; most recent closed period for QUARTERLY = 2026-Q2. */
    private static final Instant NOW = Instant.parse("2026-07-15T00:00:00Z");

    @Autowired private ApplicationContext context;
    @Autowired private EntityManager entityManager;

    private MonitorHarness h;
    private Contract contract;
    private ContractParty polestar;
    private ContractParty curasync;
    private ContractMonitor monitor;

    @BeforeEach
    void setUp() {
        h = new MonitorHarness(context, entityManager, NOW);
        contract = h.activeContract();
        polestar = h.party(contract, "Polestar Life Admin (federation host)");
        curasync = h.party(contract, "Curasync Clinical Systems");
        monitor = h.monitor(contract, MonitorRuleType.METRIC_THRESHOLD, "QUARTERLY", 0,
                ruleSpec(0.30, polestar.getId(), curasync.getId()), MonitorStatus.ACTIVE);
    }

    private ObjectNode ruleSpec(double threshold, UUID... requiredPartyIds) {
        ObjectNode spec = h.mapper.createObjectNode();
        spec.put("metricKey", "utilization_share");
        spec.put("threshold", threshold);
        var ids = spec.putArray("requiredPartyIds");
        for (UUID id : requiredPartyIds) {
            ids.add(id.toString());
        }
        return spec;
    }

    private void sample(ContractParty party, String periodKey, double value) {
        ContractMetricSample sample = new ContractMetricSample();
        sample.setContractId(contract.getId());
        sample.setPartyId(party.getId());
        sample.setMetricKey("utilization_share");
        sample.setMetricValue(value);
        sample.setPeriodKey(periodKey);
        sample.setReportedBy("agent");
        sample.setReportedAt(NOW);
        h.metricSamples.save(sample);
    }

    private MonitorEvaluation evaluate() {
        return h.metricThresholdEvaluator.evaluate(monitor, new PeriodContext(NOW, ZoneOffset.UTC));
    }

    @Test
    void zeroSamplesIsIndeterminateWithMissingPartiesInTrace() {
        // The §14.1 vector-2 pin: seeded with zero samples ⇒ first run INDETERMINATE.
        MonitorEvaluation e = evaluate();
        assertThat(e.outcome()).isEqualTo(MonitorRunOutcome.INDETERMINATE);
        assertThat(e.periodKey()).isEqualTo("2026-Q2");
        assertThat(e.breaches()).isEmpty();
        assertThat(e.trace().withArray("missingPartyIds"))
                .extracting(n -> n.asText())
                .containsExactlyInAnyOrder(polestar.getId().toString(), curasync.getId().toString());
    }

    @Test
    void partialDataIsIndeterminateNeverComputed() {
        sample(polestar, "2026-Q2", 0.80);
        MonitorEvaluation e = evaluate();
        assertThat(e.outcome()).isEqualTo(MonitorRunOutcome.INDETERMINATE);
        assertThat(e.breaches()).isEmpty();
        assertThat(e.trace().withArray("missingPartyIds"))
                .extracting(n -> n.asText())
                .containsExactly(curasync.getId().toString());
        assertThat(e.trace().has("imbalance")).as("never computed on partial data").isFalse();
    }

    @Test
    void imbalanceOverThresholdDetectsBreach() {
        sample(polestar, "2026-Q2", 0.80);
        sample(curasync, "2026-Q2", 0.20);
        MonitorEvaluation e = evaluate();
        assertThat(e.outcome()).isEqualTo(MonitorRunOutcome.BREACH_DETECTED);
        assertThat(e.trace().get("imbalance").asDouble()).isCloseTo(0.60, within());
        assertThat(e.breaches()).singleElement().satisfies(b ->
                assertThat(b.periodKey()).isEqualTo("2026-Q2"));
    }

    @Test
    void imbalanceUnderThresholdIsCompliant() {
        sample(polestar, "2026-Q2", 0.55);
        sample(curasync, "2026-Q2", 0.45);
        MonitorEvaluation e = evaluate();
        assertThat(e.outcome()).isEqualTo(MonitorRunOutcome.COMPLIANT);
        assertThat(e.breaches()).isEmpty();
    }

    @Test
    void imbalanceExactlyAtThresholdIsCompliant() {
        // §5.3: strictly "|a−b| > threshold" breaches. Exact binary fractions
        // (0.75, 0.50, 0.25) keep the boundary float-noise-free.
        monitor.setRuleSpec(ruleSpec(0.25, polestar.getId(), curasync.getId()));
        monitor = h.monitors.save(monitor);
        sample(polestar, "2026-Q2", 0.75);
        sample(curasync, "2026-Q2", 0.50);
        assertThat(evaluate().outcome()).isEqualTo(MonitorRunOutcome.COMPLIANT);
    }

    @Test
    void samplesForOtherPeriodsDoNotCount() {
        sample(polestar, "2026-Q1", 0.90);
        sample(curasync, "2026-Q1", 0.10);
        assertThat(evaluate().outcome()).isEqualTo(MonitorRunOutcome.INDETERMINATE);
    }

    @Test
    void misconfiguredRuleSpecIsIndeterminate() {
        monitor.setRuleSpec(null);
        monitor = h.monitors.save(monitor);          // capture: save bumps @Version
        assertThat(evaluate().outcome()).isEqualTo(MonitorRunOutcome.INDETERMINATE);

        ObjectNode noThreshold = h.mapper.createObjectNode();
        noThreshold.put("metricKey", "utilization_share");
        monitor.setRuleSpec(noThreshold);
        monitor = h.monitors.save(monitor);
        assertThat(evaluate().outcome()).isEqualTo(MonitorRunOutcome.INDETERMINATE);
    }

    @Test
    void moreThanTwoRequiredPartiesIsFlaggedNotInvented() {
        ContractParty third = h.party(contract, "Bursar Campus");
        monitor.setRuleSpec(ruleSpec(0.30, polestar.getId(), curasync.getId(), third.getId()));
        h.monitors.save(monitor);
        sample(polestar, "2026-Q2", 0.50);
        sample(curasync, "2026-Q2", 0.30);
        sample(third, "2026-Q2", 0.20);

        MonitorEvaluation e = evaluate();
        assertThat(e.outcome()).isEqualTo(MonitorRunOutcome.INDETERMINATE);
        assertThat(e.breaches()).isEmpty();
        assertThat(e.trace().get("note").asText()).contains("exactly two");
    }

    private static org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(1e-9);
    }
}
