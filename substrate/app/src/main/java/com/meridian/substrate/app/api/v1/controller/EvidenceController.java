package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.app.core.service.EvidenceService;
import com.meridian.substrate.app.core.service.EvidenceService.EvidenceChainEntry;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/evidence")
public class EvidenceController {

    private final EvidenceService evidenceService;

    public EvidenceController(EvidenceService evidenceService) {
        this.evidenceService = evidenceService;
    }

    @GetMapping("/entity")
    public List<EvidenceChainEntry> getForEntity(@RequestParam UUID entityId) {
        return evidenceService.getChainForEntity(entityId);
    }

    @GetMapping("/domain")
    public List<EvidenceChainEntry> getForDomain(@RequestParam UUID domainId) {
        return evidenceService.getForDomain(domainId).stream()
                .map(ev -> new EvidenceChainEntry(ev,
                        ev.getDocumentRegistryId() != null
                                ? evidenceService.getDocument(ev.getDocumentRegistryId())
                                : null))
                .toList();
    }
}
