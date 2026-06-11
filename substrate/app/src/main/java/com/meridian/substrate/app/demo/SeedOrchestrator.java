package com.meridian.substrate.app.demo;

import com.meridian.substrate.app.config.AppProperties;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.domain.OrganizationMember;
import com.meridian.substrate.common.domain.Process;
import com.meridian.substrate.common.domain.SystemEntity;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.common.repo.OrganizationMemberRepository;
import com.meridian.substrate.common.repo.ProcessRepository;
import com.meridian.substrate.common.repo.SystemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * DemoPack seed facility (port-map §5 S1). Runs demo seeding on
 * {@link ApplicationReadyEvent} when {@code substrate.demo.seed=true}
 * (default {@code false} — never the production data path; production
 * population flows through ingestion + engines only). Collects all
 * {@link Seeder} beans, topologically sorts by {@link Seeder#dependsOn()},
 * and invokes them:
 *
 * <ul>
 *   <li><b>Fresh DB</b> (no domains at all): {@link Seeder#seedBulk(SeedContext)}
 *       on each seeder in order. {@link DomainSeeder} creates the demo domain
 *       with {@code isDemo=true}; the shared {@link SeedContext} accumulates
 *       name-to-UUID maps as seeders run so downstream seeders can resolve
 *       cross-context references.</li>
 *   <li><b>Existing DB</b> (domains present): iterates each <b>{@code isDemo}</b>
 *       domain only, rebuilds the context from current DB state, and invokes
 *       {@link Seeder#seedSamplesForDomain(SeedContext, java.util.UUID)} per
 *       seeder. Only the four Phase 1 sample seeders override that method;
 *       the rest no-op, so existing dev DBs pick up new fixtures without a
 *       wipe. Non-demo domains are never touched — if the DB contains only
 *       production (non-demo) domains, this run is a no-op (substrate
 *       hardening over homepoc, which iterated every domain).</li>
 * </ul>
 *
 * <p>Boot log surfaces each seeder's execution: {@code [seed] DomainSeeder
 * completed in 2 ms}. Combined with per-seeder row-count logging inside each
 * implementation, this gives post-migration verifiers a map of what was
 * seeded where without writing SQL.
 *
 * <p><b>Transaction note:</b> the run executes in one transaction
 * (homepoc behavior) — but it is NOT fully all-or-nothing anymore.
 * {@code DecisionLogService.appendDecision} pins {@code REQUIRES_NEW} (the
 * ADR-002 D14 mirror requirement), so {@code DiagnosticSeeder}'s decision
 * appends COMMIT IMMEDIATELY and independently of this outer transaction.
 * Consequence on a mid-seed failure AFTER DiagnosticSeeder (topo position
 * 13/14 — in practice an {@code EvidenceSeeder} failure or an outer-commit
 * failure): the domain/systems/findings roll back, but the already-committed
 * {@code design_decisions} chain rows survive, soft-referencing a domain
 * UUID that never came to exist; the next boot sees zero domains, re-seeds a
 * NEW demo domain, and the orphan chain persists in any persistent DB
 * (postgres profile — in-memory H2 dev masks it). The startup verifier walks
 * orphan chains as INTACT (they are self-consistent), so the residue is
 * cosmetic, demo-lane-only garbage — accepted; clean up with
 * {@code DELETE FROM design_decisions WHERE domain_id NOT IN (SELECT id FROM
 * domains)} if it ever matters. Seeding stays strictly single-threaded, so
 * the per-domain append lock in {@code DecisionLogService} is uncontended.
 */
@Component
public class SeedOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SeedOrchestrator.class);

    private final AppProperties appProperties;
    private final List<Seeder> seeders;
    private final DomainRepository domainRepository;
    private final SystemRepository systemRepository;
    private final OrganizationMemberRepository organizationMemberRepository;
    private final ProcessRepository processRepository;

    public SeedOrchestrator(AppProperties appProperties,
                            List<Seeder> seeders,
                            DomainRepository domainRepository,
                            SystemRepository systemRepository,
                            OrganizationMemberRepository organizationMemberRepository,
                            ProcessRepository processRepository) {
        this.appProperties = appProperties;
        this.seeders = seeders;
        this.domainRepository = domainRepository;
        this.systemRepository = systemRepository;
        this.organizationMemberRepository = organizationMemberRepository;
        this.processRepository = processRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void run() {
        if (!appProperties.getDemo().isSeed()) {
            log.info("DemoPack seeding disabled (substrate.demo.seed=false)");
            return;
        }

        List<Seeder> ordered = topoSort(seeders);
        log.info("[seed] Registered {} seeder(s): {}", ordered.size(),
                ordered.stream().map(s -> s.getClass().getSimpleName()).toList());

        if (domainRepository.count() == 0) {
            runBulk(ordered);
        } else {
            runIdempotentForExistingDemoDomains(ordered);
        }
    }

    private void runBulk(List<Seeder> ordered) {
        log.info("[seed] Fresh DB — starting bulk seed");
        SeedContext ctx = new SeedContext();
        try {
            for (Seeder s : ordered) {
                long start = System.currentTimeMillis();
                s.seedBulk(ctx);
                log.info("[seed] {} completed in {} ms", s.getClass().getSimpleName(),
                        System.currentTimeMillis() - start);
            }
            log.info("[seed] Bulk seed complete [domainId={}]", ctx.getDomainId());
        } catch (Exception e) {
            log.error("[seed] Bulk seed failed: {}", e.getMessage(), e);
            throw new RuntimeException("Demo data seeding failed", e);
        }
    }

    private void runIdempotentForExistingDemoDomains(List<Seeder> ordered) {
        // Beacon SATELLITE domains (one per fixture subsidiary, created by
        // AgentDemoSeeder — engine-spec agents.md §15: a Beacon subsidiary IS
        // a Domain) are excluded from the per-domain sample iteration: the
        // Phase-1 sample seeders and the cascade fixtures belong to the
        // PRIMARY demo domain only; re-seeding them into 22 satellites would
        // multiply fixture data. AgentDemoSeeder re-converges the satellite
        // fleet itself when invoked for the primary domain.
        List<Domain> demoDomains = domainRepository.findAll().stream()
                .filter(Domain::isDemo)
                .filter(d -> d.getMetadata() == null
                        || !d.getMetadata().path("beaconSubsidiary").asBoolean(false))
                .toList();
        if (demoDomains.isEmpty()) {
            log.info("[seed] Existing domain(s) but none marked isDemo — DemoPack "
                    + "never seeds into non-demo domains; nothing to do");
            return;
        }
        log.info("[seed] Existing demo domain(s) — running per-entity idempotent sample seed");
        for (Domain d : demoDomains) {
            SeedContext ctx = buildContextForDomain(d.getId());
            log.info("[seed] --- domain [id={}, name={}] ---", d.getId(), d.getName());
            for (Seeder s : ordered) {
                long start = System.currentTimeMillis();
                s.seedSamplesForDomain(ctx, d.getId());
                long ms = System.currentTimeMillis() - start;
                // Only log seeders that had something to do; the no-op default
                // returns in <1ms so we can filter it out cheaply.
                if (ms > 0) {
                    log.info("[seed]   {} completed in {} ms",
                            s.getClass().getSimpleName(), ms);
                }
            }
        }
        log.info("[seed] Idempotent sample seed complete");
    }

    /**
     * Populate a {@link SeedContext} from the current state of an existing
     * demo domain so Phase 1 sample seeders can resolve references (e.g., pick
     * a sample system and process to attach Capability links to).
     */
    private SeedContext buildContextForDomain(UUID domainId) {
        SeedContext ctx = new SeedContext();
        ctx.setDomainId(domainId);
        for (SystemEntity s : systemRepository.findByDomainId(domainId)) {
            ctx.systemsByName().put(s.getName(), s.getId());
        }
        for (OrganizationMember m : organizationMemberRepository.findByDomainId(domainId)) {
            ctx.membersByName().put(m.getName(), m.getId());
        }
        for (Process p : processRepository.findByDomainId(domainId)) {
            ctx.processesByName().put(p.getName(), p.getId());
        }
        return ctx;
    }

    /**
     * Kahn's algorithm topological sort. Honors {@link Seeder#dependsOn()};
     * a cycle or unknown dependency is a hard boot error.
     */
    static List<Seeder> topoSort(List<Seeder> seeders) {
        Map<Class<? extends Seeder>, Seeder> byClass = new LinkedHashMap<>();
        for (Seeder s : seeders) {
            byClass.put(s.getClass(), s);
        }

        Map<Class<? extends Seeder>, Set<Class<? extends Seeder>>> remainingDeps = new LinkedHashMap<>();
        for (Seeder s : seeders) {
            Set<Class<? extends Seeder>> deps = new HashSet<>();
            for (Class<? extends Seeder> dep : s.dependsOn()) {
                if (!byClass.containsKey(dep)) {
                    throw new IllegalStateException(
                            "Seeder " + s.getClass().getSimpleName()
                                    + " declares dependency on " + dep.getSimpleName()
                                    + " but no such seeder bean is registered");
                }
                deps.add(dep);
            }
            remainingDeps.put(s.getClass(), deps);
        }

        List<Seeder> ordered = new ArrayList<>(seeders.size());
        while (!remainingDeps.isEmpty()) {
            // Find a seeder with no remaining dependencies; preserve insertion
            // order across the remaining set so ordering is stable when deps permit.
            Class<? extends Seeder> ready = null;
            for (Map.Entry<Class<? extends Seeder>, Set<Class<? extends Seeder>>> e : remainingDeps.entrySet()) {
                if (e.getValue().isEmpty()) {
                    ready = e.getKey();
                    break;
                }
            }
            if (ready == null) {
                throw new IllegalStateException(
                        "Cycle detected in Seeder dependsOn() graph across: "
                                + remainingDeps.keySet());
            }
            ordered.add(byClass.get(ready));
            remainingDeps.remove(ready);
            for (Set<Class<? extends Seeder>> deps : remainingDeps.values()) {
                deps.remove(ready);
            }
        }
        return ordered;
    }
}
