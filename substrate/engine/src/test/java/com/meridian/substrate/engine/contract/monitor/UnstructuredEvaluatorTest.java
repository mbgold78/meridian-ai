package com.meridian.substrate.engine.contract.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.ContractMonitor;
import com.meridian.substrate.common.domain.enums.MonitorRuleType;
import com.meridian.substrate.common.domain.enums.MonitorRunOutcome;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §5.3 UNSTRUCTURED matrix (engine-spec contracts.md §14.3): always
 * INDETERMINATE + HUMAN_VERIFY_DUE — a monitor can be weak but never silently
 * inert, and never COMPLIANT, never a breach (ADR D10).
 */
class UnstructuredEvaluatorTest {

    private final UnstructuredEvaluator evaluator =
            new UnstructuredEvaluator(new ObjectMapper());

    @Test
    void alwaysIndeterminateWithHumanVerifySignalAndNeverABreach() {
        ContractMonitor monitor = new ContractMonitor();
        monitor.setContractId(UUID.randomUUID());
        monitor.setName("unstructured-x");
        monitor.setRuleType(MonitorRuleType.UNSTRUCTURED);
        monitor.setCadence("MONTHLY");

        MonitorEvaluation evaluation = evaluator.evaluate(monitor,
                new PeriodContext(Instant.parse("2026-05-09T00:00:00Z"), ZoneOffset.UTC));

        assertThat(evaluation.outcome()).isEqualTo(MonitorRunOutcome.INDETERMINATE);
        assertThat(evaluation.breaches()).isEmpty();
        assertThat(evaluation.commitmentStates()).isEmpty();
        assertThat(evaluation.periodKey()).isEqualTo("2026-05");
        assertThat(evaluation.trace().get("note").asText()).contains("HUMAN_VERIFY_DUE");
    }
}
