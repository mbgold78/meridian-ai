package com.meridian.substrate.app.core.service;

import com.meridian.substrate.app.core.exception.EntityNotFoundException;
import com.meridian.substrate.common.domain.Finding;
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
     * Retrieve all findings belonging to a given domain.
     *
     * @param domainId the domain UUID
     * @return list of findings in the domain
     */
    @Transactional(readOnly = true)
    public List<Finding> getFindingsByDomain(UUID domainId) {
        log.debug("Fetching findings for domain [domainId={}]", domainId);
        return findingRepository.findByDomainId(domainId);
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
