package com.meridian.substrate.common.domain.enums;

/**
 * Action vocabulary of the append-only {@code ai_curation_events} audit
 * (engine-spec diagnostic.md §3.1/§9; ADR-004 D3/D4).
 *
 * <ul>
 *   <li>{@code ACCEPT} — a named human verified semantic support and accepted
 *       the AI claim (the S3 compensating control; for DRAFT subjects this
 *       materializes the entity and records {@code created_entity_id}).</li>
 *   <li>{@code REJECT} — rationale is service-enforced NOT NULL; rejection is
 *       a product outcome, never error-colored (HTTP 200 always).</li>
 *   <li>{@code SUPERSEDE} — system-authored at run finalization ONLY
 *       (actor 'system:diagnostic', {@code synthesis_run_id} = the superseding
 *       run); there is no operator supersede verb.</li>
 * </ul>
 *
 * <p>There is deliberately NO bulk-accept action: per-item acceptance is
 * structural (P6/doc-07; ADR-004 D9).
 */
public enum CurationAction {
    ACCEPT,
    REJECT,
    SUPERSEDE
}
