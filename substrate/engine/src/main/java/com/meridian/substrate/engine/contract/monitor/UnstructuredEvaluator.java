package com.meridian.substrate.engine.contract.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.ContractMonitor;
import com.meridian.substrate.common.domain.enums.MonitorRuleType;
import com.meridian.substrate.common.domain.enums.MonitorRunOutcome;
import org.springframework.stereotype.Component;

/**
 * §5.3 UNSTRUCTURED — "always INDETERMINATE + {@code HUMAN_VERIFY_DUE} signal
 * on each cadence tick. A monitor can be weak but never silently inert"
 * (ADR D10 graft): mechanism prose that did not compile to a structured rule
 * still demands a recurring human look. The signal class materializes at read
 * time off {@code last_outcome=INDETERMINATE} (§11.2), and the
 * structured-vs-unstructured coverage ratio keeps the degradation visible on
 * the operator signals feed (spec §16 carried risk).
 */
@Component
public class UnstructuredEvaluator implements MonitorEvaluator {

    private final ObjectMapper objectMapper;

    public UnstructuredEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public MonitorRuleType ruleType() {
        return MonitorRuleType.UNSTRUCTURED;
    }

    @Override
    public MonitorEvaluation evaluate(ContractMonitor monitor, PeriodContext ctx) {
        ObjectNode trace = objectMapper.createObjectNode();
        trace.put("rule", MonitorRuleType.UNSTRUCTURED.name());
        String period = PeriodKeys.periodKey(monitor.getCadence(), ctx.now(), ctx.zone());
        trace.put("periodKey", period);
        trace.put("note", "unstructured mechanism — human verification due each cadence tick"
                + " (HUMAN_VERIFY_DUE); never COMPLIANT, never a breach (ADR D10)");
        return MonitorEvaluation.of(MonitorRunOutcome.INDETERMINATE, period, trace);
    }
}
