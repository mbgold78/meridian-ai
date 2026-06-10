package com.meridian.substrate.app.core.service;

import com.meridian.substrate.common.domain.OrganizationMember;
import com.meridian.substrate.common.domain.RoleOverlap;
import com.meridian.substrate.common.repo.OrganizationMemberRepository;
import com.meridian.substrate.common.repo.RoleOverlapRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read-side service for organization members and role overlaps.
 */
@Service
public class OrganizationService {

    private static final Logger log = LoggerFactory.getLogger(OrganizationService.class);

    private final OrganizationMemberRepository organizationMemberRepository;
    private final RoleOverlapRepository roleOverlapRepository;

    public OrganizationService(OrganizationMemberRepository organizationMemberRepository,
                               RoleOverlapRepository roleOverlapRepository) {
        this.organizationMemberRepository = organizationMemberRepository;
        this.roleOverlapRepository = roleOverlapRepository;
    }

    /**
     * Retrieve all organization members belonging to a given domain.
     *
     * @param domainId the domain UUID
     * @return list of organization members in the domain
     */
    @Transactional(readOnly = true)
    public List<OrganizationMember> getMembersByDomain(UUID domainId) {
        log.debug("Fetching organization members for domain [domainId={}]", domainId);
        return organizationMemberRepository.findByDomainId(domainId);
    }

    /**
     * Retrieve all role overlaps belonging to a given domain.
     *
     * @param domainId the domain UUID
     * @return list of role overlaps in the domain
     */
    @Transactional(readOnly = true)
    public List<RoleOverlap> getRoleOverlapsByDomain(UUID domainId) {
        log.debug("Fetching role overlaps for domain [domainId={}]", domainId);
        return roleOverlapRepository.findByDomainId(domainId);
    }
}
