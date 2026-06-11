package com.meridian.substrate.engine.diagnostic;

import com.meridian.substrate.common.domain.Evidence;
import com.meridian.substrate.common.repo.EvidenceRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The single v1 {@link GroundingVerifier}: existence-only resolution against
 * the domain's Evidence pool (engine-spec diagnostic.md §6.2). Registered as
 * the {@code @ConditionalOnMissingBean} default in
 * {@link DiagnosticEngineConfig} — replacing the bean is the
 * EXTENSION POINT(diagnostic-1) seam, and any replacement is advisory-only by
 * contract (may lower confidence, never auto-pass).
 */
public class ExistenceGroundingVerifier implements GroundingVerifier {

    private final EvidenceRepository evidenceRepository;

    public ExistenceGroundingVerifier(EvidenceRepository evidenceRepository) {
        this.evidenceRepository = evidenceRepository;
    }

    @Override
    public String mode() {
        return MODE_EXISTENCE_ONLY;
    }

    @Override
    public Map<UUID, Evidence> resolveInDomain(List<UUID> evidenceIds, UUID domainId) {
        Map<UUID, Evidence> resolved = new LinkedHashMap<>();
        if (evidenceIds == null) {
            return resolved;
        }
        for (UUID id : evidenceIds) {
            if (id == null || resolved.containsKey(id)) {
                continue;
            }
            evidenceRepository.findById(id)
                    .filter(ev -> domainId != null && domainId.equals(ev.getDomainId()))
                    .ifPresent(ev -> resolved.put(id, ev));
        }
        return resolved;
    }
}
