package com.meridian.substrate.app.core.service;

import com.meridian.substrate.app.core.exception.EntityNotFoundException;
import com.meridian.substrate.common.domain.CanonicalEntity;
import com.meridian.substrate.common.domain.Integration;
import com.meridian.substrate.common.domain.SystemEntity;
import com.meridian.substrate.common.repo.CanonicalEntityRepository;
import com.meridian.substrate.common.repo.IntegrationRepository;
import com.meridian.substrate.common.repo.SystemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read-side service for {@link SystemEntity} and related entities
 * (canonical entities, integrations).
 */
@Service
public class SystemService {

    private static final Logger log = LoggerFactory.getLogger(SystemService.class);

    private final SystemRepository systemRepository;
    private final CanonicalEntityRepository canonicalEntityRepository;
    private final IntegrationRepository integrationRepository;

    public SystemService(SystemRepository systemRepository,
                         CanonicalEntityRepository canonicalEntityRepository,
                         IntegrationRepository integrationRepository) {
        this.systemRepository = systemRepository;
        this.canonicalEntityRepository = canonicalEntityRepository;
        this.integrationRepository = integrationRepository;
    }

    /**
     * Retrieve all systems belonging to a given domain.
     *
     * @param domainId the domain UUID
     * @return list of systems in the domain
     */
    @Transactional(readOnly = true)
    public List<SystemEntity> getSystemsByDomain(UUID domainId) {
        log.debug("Fetching systems for domain [domainId={}]", domainId);
        return systemRepository.findByDomainId(domainId);
    }

    /**
     * Retrieve a single system by its identifier.
     *
     * @param id the system UUID
     * @return the system entity
     * @throws EntityNotFoundException if no system exists with the given id
     */
    @Transactional(readOnly = true)
    public SystemEntity getSystemById(UUID id) {
        log.debug("Fetching system [id={}]", id);
        return systemRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("SystemEntity", id));
    }

    /**
     * Retrieve all canonical entities belonging to a given system.
     *
     * @param systemId the system UUID
     * @return list of canonical entities for the system
     */
    @Transactional(readOnly = true)
    public List<CanonicalEntity> getEntitiesBySystem(UUID systemId) {
        log.debug("Fetching canonical entities for system [systemId={}]", systemId);
        return canonicalEntityRepository.findBySystemId(systemId);
    }

    /**
     * Retrieve all integrations where the given system is either the source or the target.
     *
     * @param systemId the system UUID
     * @return list of integrations involving the system
     */
    @Transactional(readOnly = true)
    public List<Integration> getIntegrationsBySystem(UUID systemId) {
        log.debug("Fetching integrations for system [systemId={}]", systemId);
        return integrationRepository.findBySourceSystemIdOrTargetSystemId(systemId, systemId);
    }
}
