package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.app.core.service.DomainService;
import com.meridian.substrate.common.domain.Domain;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for domain-related read operations.
 */
@RestController
@RequestMapping("/api/v1/domains")
public class DomainController {

    private final DomainService domainService;

    public DomainController(DomainService domainService) {
        this.domainService = domainService;
    }

    /**
     * Retrieve all domains.
     *
     * @return list of all domains
     */
    @GetMapping
    public List<Domain> getAllDomains() {
        return domainService.getAllDomains();
    }

    /**
     * Retrieve a single domain by its identifier.
     *
     * @param id the domain UUID
     * @return the domain
     */
    @GetMapping("/{id}")
    public Domain getDomainById(@PathVariable UUID id) {
        return domainService.getDomainById(id);
    }
}
