package com.meridian.substrate.app.demo;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mutable shared state passed between {@link Seeder}s within a single run.
 * Earlier seeders write their name-to-UUID maps; dependent seeders read them
 * to resolve cross-context references by name (since demopack JSON files use
 * human-readable names, not UUIDs, for foreign references).
 *
 * <p>Not thread-safe — seeders run sequentially in topological order under
 * the orchestrator's single transaction.
 */
public final class SeedContext {

    private UUID domainId;
    private final Map<String, UUID> systemsByName = new LinkedHashMap<>();
    private final Map<String, UUID> membersByName = new LinkedHashMap<>();
    private final Map<String, UUID> processesByName = new LinkedHashMap<>();
    private final Map<String, UUID> entitiesByName = new LinkedHashMap<>();

    public UUID getDomainId() {
        return domainId;
    }

    public void setDomainId(UUID domainId) {
        this.domainId = domainId;
    }

    public Map<String, UUID> systemsByName() {
        return systemsByName;
    }

    public Map<String, UUID> membersByName() {
        return membersByName;
    }

    public Map<String, UUID> processesByName() {
        return processesByName;
    }

    public Map<String, UUID> entitiesByName() {
        return entitiesByName;
    }
}
