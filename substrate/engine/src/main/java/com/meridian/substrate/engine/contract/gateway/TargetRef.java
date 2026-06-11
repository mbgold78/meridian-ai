package com.meridian.substrate.engine.contract.gateway;

/**
 * Optional target of a {@link GovernedAction} (engine-spec contracts.md §4.1:
 * "nullable: {entityType, entityId}"). {@code entityType} feeds the §4.3
 * guard narrowing (a guard's null {@code entity_type} is the wildcard);
 * both fields surface under {@code target.*} dot-paths in the §4.4
 * evaluation document ({@link EvaluationDocuments} — same shape as the
 * {@code check_action} advisory, which carries them as strings on the wire,
 * hence String here; UUID targets pass {@code toString()}).
 */
public record TargetRef(String entityType, String entityId) {
}
