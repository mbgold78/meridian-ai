package com.meridian.substrate.engine.contract.monitor;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractMonitor;
import com.meridian.substrate.common.domain.ReaderRegistration;
import com.meridian.substrate.common.domain.enums.MonitorRuleType;
import com.meridian.substrate.common.domain.enums.MonitorRunOutcome;
import com.meridian.substrate.common.domain.enums.MonitorStatus;
import com.meridian.substrate.common.domain.enums.ReaderKind;
import com.meridian.substrate.common.repo.ReaderRegistrationRepository;
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
 * §5.3 MCP_PROBE matrix (engine-spec contracts.md §14.3):
 * reachable/unreachable/unregistered — liveness only, NEVER a breach in any
 * row (ADR D11), unreachable/absent ⇒ INDETERMINATE (ADHERENCE_UNOBSERVABLE).
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:contract-probe-eval;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class McpProbeEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-05-09T00:00:00Z");

    @Autowired private ApplicationContext context;
    @Autowired private EntityManager entityManager;
    @Autowired private ReaderRegistrationRepository readerRegistrations;

    private MonitorHarness h;
    private Contract contract;

    @BeforeEach
    void setUp() {
        h = new MonitorHarness(context, entityManager, NOW);
        contract = h.activeContract();
    }

    private ContractMonitor probeMonitor(UUID readerRegistrationId) {
        ObjectNode spec = h.mapper.createObjectNode();
        if (readerRegistrationId != null) {
            spec.put("readerRegistrationId", readerRegistrationId.toString());
        }
        return h.monitor(contract, MonitorRuleType.MCP_PROBE, "MONTHLY", 0, spec, MonitorStatus.ACTIVE);
    }

    private ReaderRegistration registration(ReaderKind kind, String uri) {
        ReaderRegistration registration = new ReaderRegistration();
        registration.setDomainId(contract.getDomainId());
        registration.setKind(kind);
        registration.setName("party-mcp-endpoint");
        registration.setUri(uri);
        return readerRegistrations.save(registration);
    }

    private MonitorEvaluation evaluate(ContractMonitor monitor) {
        return h.mcpProbeEvaluator.evaluate(monitor, new PeriodContext(NOW, ZoneOffset.UTC));
    }

    @Test
    void reachableEndpointIsCompliant() {
        h.probe.set(uri -> true);
        ReaderRegistration registration = registration(ReaderKind.MCP_CLIENT, "http://party.example/mcp");
        MonitorEvaluation e = evaluate(probeMonitor(registration.getId()));
        assertThat(e.outcome()).isEqualTo(MonitorRunOutcome.COMPLIANT);
        assertThat(e.breaches()).isEmpty();
        assertThat(e.trace().get("reachable").asBoolean()).isTrue();
    }

    @Test
    void unreachableEndpointIsIndeterminateNeverABreach() {
        h.probe.set(uri -> false);
        ReaderRegistration registration = registration(ReaderKind.MCP_CLIENT, "http://party.example/mcp");
        MonitorEvaluation e = evaluate(probeMonitor(registration.getId()));
        assertThat(e.outcome()).isEqualTo(MonitorRunOutcome.INDETERMINATE);
        assertThat(e.breaches()).isEmpty();
        assertThat(e.trace().get("note").asText()).contains("ADHERENCE_UNOBSERVABLE");
    }

    @Test
    void missingRegistrationIsIndeterminate() {
        MonitorEvaluation e = evaluate(probeMonitor(UUID.randomUUID()));
        assertThat(e.outcome()).isEqualTo(MonitorRunOutcome.INDETERMINATE);
        assertThat(e.breaches()).isEmpty();
    }

    @Test
    void nonMcpClientRegistrationIsIndeterminate() {
        ReaderRegistration registration = registration(ReaderKind.STRUCTURED, "http://party.example/api");
        MonitorEvaluation e = evaluate(probeMonitor(registration.getId()));
        assertThat(e.outcome()).isEqualTo(MonitorRunOutcome.INDETERMINATE);
        assertThat(e.breaches()).isEmpty();
    }

    @Test
    void missingRuleSpecIdIsIndeterminate() {
        MonitorEvaluation e = evaluate(probeMonitor(null));
        assertThat(e.outcome()).isEqualTo(MonitorRunOutcome.INDETERMINATE);
        assertThat(e.breaches()).isEmpty();
    }
}
