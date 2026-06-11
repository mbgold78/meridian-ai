package com.meridian.substrate.app.api.v1.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * {@code POST /agents/{idOrKey}/oversight} body (engine-spec agents.md §5.1
 * M14/§7.2): FULL replacement of the ordered escalation chain, before/after
 * evented (OVERSIGHT_CHANGED) — accountability routing evolves; the mandate
 * does not (ADR-003 D5).
 */
public record AgentOversightRequest(
        @NotBlank @Size(max = 128) String idempotencyKey,
        @NotEmpty @Valid List<Entry> chain,
        Integer expectedEventSeq
) {

    /** One ordered chain entry (label required; memberId is a soft ref). */
    public record Entry(String label, UUID memberId) {
    }
}
