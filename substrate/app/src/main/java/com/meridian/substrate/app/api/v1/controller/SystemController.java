package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.app.core.service.SystemService;
import com.meridian.substrate.common.domain.CanonicalEntity;
import com.meridian.substrate.common.domain.Integration;
import com.meridian.substrate.common.domain.SystemEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for system-related read operations.
 */
@RestController
@RequestMapping("/api/v1/systems")
public class SystemController {

    private final SystemService systemService;

    public SystemController(SystemService systemService) {
        this.systemService = systemService;
    }

    /**
     * Retrieve all systems belonging to a given domain.
     *
     * @param domainId the domain UUID
     * @return list of systems in the domain
     */
    @GetMapping
    public List<SystemEntity> getSystemsByDomain(@RequestParam UUID domainId) {
        return systemService.getSystemsByDomain(domainId);
    }

    /**
     * Retrieve a single system by its identifier.
     *
     * @param id the system UUID
     * @return the system entity
     */
    @GetMapping("/{id}")
    public SystemEntity getSystemById(@PathVariable UUID id) {
        return systemService.getSystemById(id);
    }

    /**
     * Retrieve all canonical entities belonging to a given system.
     *
     * @param id the system UUID
     * @return list of canonical entities for the system
     */
    @GetMapping("/{id}/entities")
    public List<CanonicalEntity> getEntitiesBySystem(@PathVariable UUID id) {
        return systemService.getEntitiesBySystem(id);
    }

    /**
     * Retrieve all integrations where the given system is source or target.
     *
     * @param id the system UUID
     * @return list of integrations involving the system
     */
    @GetMapping("/{id}/integrations")
    public List<Integration> getIntegrationsBySystem(@PathVariable UUID id) {
        return systemService.getIntegrationsBySystem(id);
    }
}
