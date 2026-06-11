package com.meridian.substrate.app.api.v1.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /agents/{idOrKey}/throttle} body (engine-spec agents.md §5.1
 * M8/§7.2; ADR-003 D12): {@code budgetKind} is a validated string against the
 * §3 allowlist (service-checked so the message lists the vocabulary).
 */
public record AgentThrottleRequest(
        @NotBlank @Size(max = 128) String idempotencyKey,
        String budgetKind,
        Double limitValue,
        Integer windowMinutes,
        @Size(max = 512) String note,
        Integer expectedEventSeq
) {
}
