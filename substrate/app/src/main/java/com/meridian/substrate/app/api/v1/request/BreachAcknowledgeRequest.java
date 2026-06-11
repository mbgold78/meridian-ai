package com.meridian.substrate.app.api.v1.request;

/**
 * {@code POST /breaches/{id}/acknowledge} body (engine-spec contracts.md
 * §7.2/§2.4): SURFACED → ACKNOWLEDGED by a named actor from persona context;
 * {@code note} optional (the body itself may be omitted). Agents MAY
 * acknowledge (ADR D16 restricts only waiver).
 */
public record BreachAcknowledgeRequest(String note) {}
