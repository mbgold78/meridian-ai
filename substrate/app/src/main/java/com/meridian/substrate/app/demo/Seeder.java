package com.meridian.substrate.app.demo;

import java.util.List;
import java.util.UUID;

/**
 * One bounded context of the DemoPack seed data. Implementations are @Components;
 * {@link SeedOrchestrator} collects them, topologically sorts by {@link #dependsOn()},
 * and invokes {@link #seedBulk(SeedContext)} (fresh DB) or
 * {@link #seedSamplesForDomain(SeedContext, UUID)} (existing demo domains) in order.
 *
 * <p>One-responsibility rule: each Seeder owns exactly the entities of its
 * bounded context. Cross-context references are resolved via the shared
 * {@link SeedContext} name-to-UUID maps that prior seeders populate.
 *
 * <p><b>DemoPack contract (port-map §5 S1):</b> seeders only ever write into
 * {@code isDemo} domains. They are never the production data path — production
 * population flows through the ingestion module and the engines.
 */
public interface Seeder {

    /**
     * Seed this bounded context from scratch. Called only when the DB has no
     * existing domains (bulk first-time seed). Reads dependency data from
     * {@code ctx} that earlier seeders wrote; writes its own name-to-UUID map
     * back into {@code ctx} for downstream seeders.
     */
    void seedBulk(SeedContext ctx);

    /**
     * Seed the Phase 1 samples for a specific existing <b>demo</b> domain.
     * Called when the DB already has domains — the orchestrator iterates each
     * {@code isDemo} domain and invokes this per seeder, per-entity-absent
     * (idempotent re-entry). Non-demo domains are never passed in.
     *
     * <p>Default: no-op. Seeders that added net-new Phase 1 entity types
     * (CapabilitySeeder, ProcessSeeder's step sample, ReaderSeeder, PhaseSeeder)
     * override this so pre-Phase-1 dev DBs pick up the new fixtures without a
     * full wipe. Other seeders leave it as no-op.
     */
    default void seedSamplesForDomain(SeedContext ctx, UUID domainId) {
        // no-op
    }

    /**
     * Seeders that must complete before this one runs. Empty list = no deps.
     * The orchestrator's topological sort honors this to produce a safe order;
     * cycles are a hard error at boot.
     */
    List<Class<? extends Seeder>> dependsOn();
}
