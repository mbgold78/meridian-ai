package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.app.core.service.OrganizationService;
import com.meridian.substrate.common.domain.OrganizationMember;
import com.meridian.substrate.common.domain.RoleOverlap;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for organization-related read operations including
 * members and role overlaps.
 */
@RestController
@RequestMapping("/api/v1/organization")
public class OrganizationController {

    private final OrganizationService organizationService;

    public OrganizationController(OrganizationService organizationService) {
        this.organizationService = organizationService;
    }

    /**
     * Retrieve all organization members belonging to a given domain.
     *
     * @param domainId the domain UUID
     * @return list of organization members
     */
    @GetMapping("/members")
    public List<OrganizationMember> getMembersByDomain(@RequestParam UUID domainId) {
        return organizationService.getMembersByDomain(domainId);
    }

    /**
     * Retrieve all role overlaps belonging to a given domain.
     *
     * @param domainId the domain UUID
     * @return list of role overlaps
     */
    @GetMapping("/role-overlaps")
    public List<RoleOverlap> getRoleOverlapsByDomain(@RequestParam UUID domainId) {
        return organizationService.getRoleOverlapsByDomain(domainId);
    }
}
