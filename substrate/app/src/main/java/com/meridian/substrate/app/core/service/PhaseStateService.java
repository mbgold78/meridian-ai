package com.meridian.substrate.app.core.service;

import com.meridian.substrate.common.domain.PhaseState;
import com.meridian.substrate.common.domain.enums.LifecyclePhase;
import com.meridian.substrate.common.domain.enums.PhaseStatus;
import com.meridian.substrate.common.repo.PhaseStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Scaffolded lifecycle phase-state service. Phase 1 scope is intentionally
 * placeholder: it can initialize a domain's phase rows and return current
 * states. Actual checkpoint evaluation (reading across Domain, Process, System,
 * Finding, ReaderRegistration) is deferred to the phase-specific slices
 * (doc 12 Phase 2/3/4). This class exists so upstream scaffolding can depend
 * on a stable service interface.
 */
@Service
public class PhaseStateService {

    private static final Logger log = LoggerFactory.getLogger(PhaseStateService.class);

    private final PhaseStateRepository repository;

    public PhaseStateService(PhaseStateRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<PhaseState> listByDomain(UUID domainId) {
        return repository.findByDomainId(domainId);
    }

    @Transactional(readOnly = true)
    public PhaseState getOrPlaceholder(UUID domainId, LifecyclePhase phase) {
        return repository.findByDomainIdAndPhase(domainId, phase)
                .orElseGet(() -> placeholder(domainId, phase));
    }

    /**
     * Create NOT_STARTED rows for every phase in the canonical order. Idempotent:
     * skips phases that already have rows. Called manually in Phase 1; later
     * phases may wire this into domain-creation flows.
     */
    @Transactional
    public List<PhaseState> initializeForDomain(UUID domainId) {
        EnumSet<LifecyclePhase> existing = EnumSet.noneOf(LifecyclePhase.class);
        for (PhaseState ps : repository.findByDomainId(domainId)) {
            existing.add(ps.getPhase());
        }
        for (LifecyclePhase p : LifecyclePhase.values()) {
            if (existing.contains(p)) continue;
            PhaseState ps = new PhaseState();
            ps.setDomainId(domainId);
            ps.setPhase(p);
            ps.setStatus(PhaseStatus.NOT_STARTED);
            ps.setCreatedBy("phase-state-service");
            repository.save(ps);
        }
        return repository.findByDomainId(domainId);
    }

    /**
     * Placeholder checkpoint evaluation — Phase 1 stub. Always returns the
     * persisted state unchanged, or a non-persisted NOT_STARTED row if absent.
     * Later phases replace this with actual cross-entity evaluation.
     */
    private PhaseState placeholder(UUID domainId, LifecyclePhase phase) {
        log.debug("PhaseStateService placeholder for domain={} phase={}", domainId, phase);
        PhaseState ps = new PhaseState();
        ps.setDomainId(domainId);
        ps.setPhase(phase);
        ps.setStatus(PhaseStatus.NOT_STARTED);
        return ps;
    }
}
