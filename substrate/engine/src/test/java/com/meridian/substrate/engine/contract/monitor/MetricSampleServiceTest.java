package com.meridian.substrate.engine.contract.monitor;

import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractMetricSample;
import com.meridian.substrate.common.domain.ContractParty;
import com.meridian.substrate.engine.contract.registry.ContractActor;
import com.meridian.substrate.engine.contract.registry.ContractEventService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §5.5 MetricSampleService (engine-spec contracts.md §14.3): idempotent
 * upsert on (contract, party, metric, period) + METRIC_REPORTED event per
 * submission; party-on-contract validation; identity recorded verbatim
 * (advisory pre-Phase-5, spec §15 #12).
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:contract-metric-svc;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MetricSampleServiceTest {

    private static final Instant T0 = Instant.parse("2026-07-15T00:00:00Z");
    private static final ContractActor AGENT =
            new ContractActor(com.meridian.substrate.common.domain.enums.ContractActorType.AGENT,
                    "polestar-prefill-agent");

    @Autowired private ApplicationContext context;
    @Autowired private EntityManager entityManager;

    private MonitorHarness h;
    private Contract contract;
    private ContractParty party;

    @BeforeEach
    void setUp() {
        h = new MonitorHarness(context, entityManager, T0);
        contract = h.activeContract();
        party = h.party(contract, "Polestar Life Admin (federation host)");
    }

    private MetricSampleService.ReportMetricCommand cmd(double value) {
        return new MetricSampleService.ReportMetricCommand(contract.getId(), party.getId(),
                "utilization_share", value, "2026-Q2", "polestar-prefill-agent", AGENT);
    }

    @Test
    void reportUpsertsOneRowPerKeyAndAppendsEveryEvent() {
        ContractMetricSample first = h.metricSampleService.report(cmd(0.62));
        ContractMetricSample second = h.metricSampleService.report(cmd(0.58));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getMetricValue()).isEqualTo(0.58);
        assertThat(h.metricSamples.findByContractIdAndMetricKeyAndPeriodKey(
                contract.getId(), "utilization_share", "2026-Q2")).hasSize(1);
        assertThat(h.eventCount(contract.getId(), ContractEventService.METRIC_REPORTED)).isEqualTo(2);
    }

    @Test
    void differentPartiesAreDistinctRows() {
        ContractParty other = h.party(contract, "Curasync Clinical Systems");
        h.metricSampleService.report(cmd(0.62));
        h.metricSampleService.report(new MetricSampleService.ReportMetricCommand(
                contract.getId(), other.getId(), "utilization_share", 0.38, "2026-Q2",
                "curasync-agent", AGENT));
        assertThat(h.metricSamples.findByContractIdAndMetricKeyAndPeriodKey(
                contract.getId(), "utilization_share", "2026-Q2")).hasSize(2);
    }

    @Test
    void partyMustBelongToTheContract() {
        Contract other = h.activeContract();
        ContractParty foreign = h.party(other, "Foreign Party");
        assertThatThrownBy(() -> h.metricSampleService.report(
                new MetricSampleService.ReportMetricCommand(contract.getId(), foreign.getId(),
                        "utilization_share", 0.5, "2026-Q2", "x", AGENT)))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void mandatoryFieldsAreServiceEnforced() {
        assertThatThrownBy(() -> h.metricSampleService.report(
                new MetricSampleService.ReportMetricCommand(contract.getId(), party.getId(),
                        " ", 0.5, "2026-Q2", "x", AGENT)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("metricKey");
        assertThatThrownBy(() -> h.metricSampleService.report(
                new MetricSampleService.ReportMetricCommand(contract.getId(), party.getId(),
                        "utilization_share", 0.5, null, "x", AGENT)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("periodKey");
    }
}
