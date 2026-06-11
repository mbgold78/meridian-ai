package com.meridian.substrate.app.core.service;

import com.meridian.substrate.app.core.exception.EntityNotFoundException;
import com.meridian.substrate.common.domain.Finding;
import com.meridian.substrate.common.domain.enums.CurationStatus;
import com.meridian.substrate.common.domain.enums.FindingType;
import com.meridian.substrate.common.repo.FindingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read-side service for {@link Finding} entities.
 */
@Service
public class FindingService {

    private static final Logger log = LoggerFactory.getLogger(FindingService.class);

    private final FindingRepository findingRepository;

    public FindingService(FindingRepository findingRepository) {
        this.findingRepository = findingRepository;
    }

    /**
     * Retrieve all findings belonging to a given domain, with the curation
     * DEFAULT filter applied (engine-spec diagnostic.md §9; ADR-004 D10):
     * {@code curation_status NOT IN (REJECTED, SUPERSEDED) OR curation_status
     * IS NULL}. NULL = not subject to curation (operator/fixture rows —
     * DiagnosticSeeder rows stay NULL, demo behavior unchanged); a rejected
     * AI claim never silently re-inflates a list.
     */
    @Transactional(readOnly = true)
    public List<Finding> getFindingsByDomain(UUID domainId) {
        return getFindingsByDomain(domainId, null, false, null);
    }

    /**
     * The §9 list surface: additive {@code curationStatus} /
     * {@code includeRejected} / {@code aiGenerated} filters over the domain's
     * findings.
     *
     * <ul>
     *   <li>{@code curationStatus != null} — explicit filter; overrides the
     *       default exclusion (asking for REJECTED shows REJECTED).</li>
     *   <li>{@code includeRejected=true} — restores the default-excluded
     *       REJECTED/SUPERSEDED rows.</li>
     *   <li>{@code aiGenerated != null} — true ⇒ AI rows only; false ⇒
     *       operator/fixture rows (a null entity flag counts as false).</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public List<Finding> getFindingsByDomain(UUID domainId, CurationStatus curationStatus,
                                             boolean includeRejected, Boolean aiGenerated) {
        log.debug("Fetching findings for domain [domainId={}, curationStatus={}, "
                        + "includeRejected={}, aiGenerated={}]",
                domainId, curationStatus, includeRejected, aiGenerated);
        return findingRepository.findByDomainId(domainId).stream()
                .filter(f -> aiGenerated == null
                        || aiGenerated.equals(Boolean.TRUE.equals(f.getAiGenerated())))
                .filter(f -> matchesCurationFilter(f, curationStatus, includeRejected))
                .toList();
    }

    private static boolean matchesCurationFilter(Finding finding, CurationStatus requested,
                                                 boolean includeRejected) {
        if (requested != null) {
            return requested == finding.getCurationStatus();
        }
        if (includeRejected) {
            return true;
        }
        CurationStatus status = finding.getCurationStatus();
        return status != CurationStatus.REJECTED && status != CurationStatus.SUPERSEDED;
    }

    /**
     * Retrieve all findings of a given type.
     *
     * @param type the finding type to filter by
     * @return list of findings matching the type
     */
    @Transactional(readOnly = true)
    public List<Finding> getFindingsByType(FindingType type) {
        log.debug("Fetching findings by type [type={}]", type);
        return findingRepository.findByFindingType(type);
    }

    /**
     * Retrieve a single finding by its identifier.
     *
     * @param id the finding UUID
     * @return the finding
     * @throws EntityNotFoundException if no finding exists with the given id
     */
    @Transactional(readOnly = true)
    public Finding getFindingById(UUID id) {
        log.debug("Fetching finding [id={}]", id);
        return findingRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Finding", id));
    }
}
