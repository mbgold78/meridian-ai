package com.meridian.substrate.engine.contract.read;

import java.util.UUID;

/**
 * One {@code contract_entity_links} row / {@code bound_entity_ids} element on
 * the wire (engine-spec contracts.md §7.3): {@code entityId} may be null
 * during the pre-D5 window, where the fixture {@code slug} is the fallback
 * reference ({@code linkedSubsidiarySlugs} seam — the S5 adapter maps
 * {@code linkedSubsidiarySlugs ← linkedEntities[].slug} for type
 * {@code SUBSIDIARY_SLUG}).
 */
public record LinkedEntityRef(String entityType, UUID entityId, String slug, String label) {
}
