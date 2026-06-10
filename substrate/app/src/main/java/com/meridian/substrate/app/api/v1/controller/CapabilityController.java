package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.app.api.v1.request.CapabilityCreateRequest;
import com.meridian.substrate.app.api.v1.request.CapabilityLinkRequest;
import com.meridian.substrate.app.api.v1.request.CapabilityReparentRequest;
import com.meridian.substrate.app.api.v1.request.CapabilitySequenceRequest;
import com.meridian.substrate.app.api.v1.request.CapabilityUpdateRequest;
import com.meridian.substrate.app.capability.service.CapabilityService;
import com.meridian.substrate.common.domain.Capability;
import com.meridian.substrate.common.domain.CapabilityNameHistory;
import com.meridian.substrate.common.domain.CapabilityProcessLink;
import com.meridian.substrate.common.domain.CapabilitySystemLink;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/capabilities")
public class CapabilityController {

    private final CapabilityService capabilityService;

    public CapabilityController(CapabilityService capabilityService) {
        this.capabilityService = capabilityService;
    }

    @GetMapping
    public List<Capability> listByDomain(@RequestParam UUID domainId) {
        return capabilityService.listByDomain(domainId);
    }

    @GetMapping("/{id}")
    public Capability get(@PathVariable UUID id) {
        return capabilityService.get(id);
    }

    @PostMapping
    public ResponseEntity<Capability> create(@Valid @RequestBody CapabilityCreateRequest req) {
        Capability saved = capabilityService.create(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PatchMapping("/{id}")
    public Capability update(@PathVariable UUID id, @RequestBody CapabilityUpdateRequest req) {
        return capabilityService.update(id, req);
    }

    /**
     * Move a capability under a new parent (or to root with
     * {@code newParentId: null}). Dedicated endpoint rather than folding
     * parentCapabilityId into the general PATCH body — see
     * {@code CapabilityUpdateRequest} Javadoc.
     */
    @PatchMapping("/{id}/parent")
    public Capability reparent(@PathVariable UUID id, @RequestBody CapabilityReparentRequest req) {
        return capabilityService.reparent(id, req.newParentId());
    }

    /**
     * Set a capability's 0-based position within its current sibling
     * group. The service rewrites sibling {@code sequenceOrder} values as
     * contiguous 0..N-1 after the move. Dedicated endpoint rather than
     * folding into general PATCH — see {@code CapabilitySequenceRequest}.
     */
    @PatchMapping("/{id}/sequence")
    public Capability reorder(@PathVariable UUID id, @RequestBody CapabilitySequenceRequest req) {
        return capabilityService.reorder(id, req.newSequence());
    }

    @GetMapping("/{id}/name-history")
    public List<CapabilityNameHistory> getNameHistory(@PathVariable UUID id) {
        return capabilityService.getNameHistory(id);
    }

    // ---- process links ------------------------------------------------------

    @GetMapping("/{id}/process-links")
    public List<CapabilityProcessLink> getProcessLinks(@PathVariable UUID id) {
        return capabilityService.getProcessLinks(id);
    }

    @PostMapping("/process-links")
    public ResponseEntity<CapabilityProcessLink> createProcessLink(@Valid @RequestBody CapabilityLinkRequest req) {
        CapabilityProcessLink saved = capabilityService.createProcessLink(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @DeleteMapping("/process-links/{linkId}")
    public ResponseEntity<Void> deleteProcessLink(@PathVariable UUID linkId) {
        capabilityService.deleteProcessLink(linkId);
        return ResponseEntity.noContent().build();
    }

    // ---- system links -------------------------------------------------------

    @GetMapping("/{id}/system-links")
    public List<CapabilitySystemLink> getSystemLinks(@PathVariable UUID id) {
        return capabilityService.getSystemLinks(id);
    }

    @PostMapping("/system-links")
    public ResponseEntity<CapabilitySystemLink> createSystemLink(@Valid @RequestBody CapabilityLinkRequest req) {
        CapabilitySystemLink saved = capabilityService.createSystemLink(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @DeleteMapping("/system-links/{linkId}")
    public ResponseEntity<Void> deleteSystemLink(@PathVariable UUID linkId) {
        capabilityService.deleteSystemLink(linkId);
        return ResponseEntity.noContent().build();
    }
}
