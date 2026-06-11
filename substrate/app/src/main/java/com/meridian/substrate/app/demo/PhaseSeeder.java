package com.meridian.substrate.app.demo;

import com.meridian.substrate.app.core.service.PhaseStateService;
import com.meridian.substrate.common.domain.enums.LifecyclePhase;
import com.meridian.substrate.common.domain.enums.PhaseStatus;
import com.meridian.substrate.common.repo.PhaseStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Seeds initial phase_states for a demo domain. Delegates to
 * {@link PhaseStateService#initializeForDomain(UUID)}, which is itself
 * idempotent — no-ops if phase states already exist for the domain.
 *
 * <p><b>D5 acceptance lever (engine-spec diagnostic.md §14 step 9):</b> after
 * initialization, the demo domain's DESIGN phase is promoted to IN_PROGRESS
 * ({@code enteredAt} = seed time). Under a normal clock the domain reads as
 * in-Design with ~zero dwell → {@code lifecycleStallCount=0}; booting with
 * {@code substrate.demo.fixed-clock} pinned MORE than the §12.1 stall
 * threshold past seed time (e.g. seed-day + 91d) flips the count 0→1
 * demonstrably via {@code curl /api/v1/portfolio} — dwell computes against
 * {@code max(enteredAt, updatedAt)}, and both are the seed instant. The
 * promotion is idempotent: only a row still NOT_STARTED is touched, so later
 * operator edits survive re-seeding. Beacon satellite domains
 * (AgentDemoSeeder) deliberately keep zero phase rows — they ARE the §12.1
 * {@code unphased} count, the needs-input class that must never fold into
 * stalls.
 */
@Component
public class PhaseSeeder implements Seeder {

    private static final Logger log = LoggerFactory.getLogger(PhaseSeeder.class);

    /** The phase the DemoPack narrative places the primary demo domain in. */
    static final LifecyclePhase SEEDED_CURRENT_PHASE = LifecyclePhase.DESIGN;

    private final PhaseStateService phaseStateService;
    private final PhaseStateRepository phaseStateRepository;

    public PhaseSeeder(PhaseStateService phaseStateService,
                       PhaseStateRepository phaseStateRepository) {
        this.phaseStateService = phaseStateService;
        this.phaseStateRepository = phaseStateRepository;
    }

    @Override
    public void seedBulk(SeedContext ctx) {
        seedSamplesForDomain(ctx, ctx.getDomainId());
    }

    @Override
    public void seedSamplesForDomain(SeedContext ctx, UUID domainId) {
        phaseStateService.initializeForDomain(domainId);
        promoteSeededCurrentPhase(domainId);
        log.info("[seed] PhaseSeeder: phase_states initialized [domainId={}, {} IN_PROGRESS]",
                domainId, SEEDED_CURRENT_PHASE);
    }

    /**
     * Idempotent: promotes DESIGN → IN_PROGRESS only while still NOT_STARTED.
     *
     * <p>{@code enteredAt} is DELIBERATELY wall-clock ({@code Instant.now()}),
     * not the injected engine {@code Clock}: row write-times
     * ({@code @CreationTimestamp}/{@code @UpdateTimestamp}) are wall-clock,
     * and the §12.1 dwell computes against {@code max(enteredAt, updatedAt)}
     * — stamping a FUTURE-pinned fixed-clock instant here would zero the
     * dwell and kill the 0→1 flip the lever exists to demonstrate. The
     * fixed-clock lever pins engine "now" (S15), never row write-times.
     */
    private void promoteSeededCurrentPhase(UUID domainId) {
        phaseStateRepository.findByDomainIdAndPhase(domainId, SEEDED_CURRENT_PHASE)
                .filter(ps -> ps.getStatus() == PhaseStatus.NOT_STARTED)
                .ifPresent(ps -> {
                    ps.setStatus(PhaseStatus.IN_PROGRESS);
                    ps.setEnteredAt(Instant.now());
                    phaseStateRepository.save(ps);
                });
    }

    @Override
    public List<Class<? extends Seeder>> dependsOn() {
        return List.of(DomainSeeder.class);
    }
}
