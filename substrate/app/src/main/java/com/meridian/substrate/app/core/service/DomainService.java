package com.meridian.substrate.app.core.service;

import com.meridian.substrate.app.core.exception.EntityNotFoundException;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.repo.DomainRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read-side service for {@link Domain} entities.
 */
@Service
public class DomainService {

    private static final Logger log = LoggerFactory.getLogger(DomainService.class);

    private final DomainRepository domainRepository;

    public DomainService(DomainRepository domainRepository) {
        this.domainRepository = domainRepository;
    }

    /**
     * Retrieve all domains.
     *
     * @return list of all domains
     */
    @Transactional(readOnly = true)
    public List<Domain> getAllDomains() {
        log.debug("Fetching all domains");
        return domainRepository.findAll();
    }

    /**
     * Retrieve a single domain by its identifier.
     *
     * @param id the domain UUID
     * @return the domain
     * @throws EntityNotFoundException if no domain exists with the given id
     */
    @Transactional(readOnly = true)
    public Domain getDomainById(UUID id) {
        log.debug("Fetching domain [id={}]", id);
        return domainRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Domain", id));
    }
}
