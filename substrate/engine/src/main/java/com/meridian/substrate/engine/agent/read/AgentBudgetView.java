package com.meridian.substrate.engine.agent.read;

import com.meridian.substrate.common.domain.AgentThrottleBudget;

import java.time.Instant;
import java.util.UUID;

/**
 * One throttle-budget row on the detail DTO (engine-spec agents.md §7.3;
 * ADR-003 D12). Only ACTIVE budgets render; for non-substrate loci a budget
 * is a published obligation, never a fabricated block — the verb response's
 * enforcement block carries that honesty.
 */
public record AgentBudgetView(
        UUID id,
        String budgetKind,
        Double limitValue,
        Integer windowMinutes,
        String setBy,
        Instant setAt,
        String note) {

    public static AgentBudgetView from(AgentThrottleBudget b) {
        return new AgentBudgetView(b.getId(), b.getBudgetKind(), b.getLimitValue(),
                b.getWindowMinutes(), b.getSetByLabel(), b.getSetAt(), b.getNote());
    }
}
