package com.meridian.substrate.app.core.service;

import com.meridian.substrate.common.domain.DocumentRegistry;
import com.meridian.substrate.common.domain.Evidence;
import com.meridian.substrate.common.repo.DocumentRegistryRepository;
import com.meridian.substrate.common.repo.EvidenceRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class EvidenceService {

    private final EvidenceRepository evidenceRepository;
    private final DocumentRegistryRepository documentRegistryRepository;

    public EvidenceService(EvidenceRepository evidenceRepository,
                           DocumentRegistryRepository documentRegistryRepository) {
        this.evidenceRepository = evidenceRepository;
        this.documentRegistryRepository = documentRegistryRepository;
    }

    public List<Evidence> getForEntity(UUID entityId) {
        return evidenceRepository.findByEntityId(entityId);
    }

    public List<Evidence> getForDomain(UUID domainId) {
        return evidenceRepository.findByDomainId(domainId);
    }

    public DocumentRegistry getDocument(UUID documentRegistryId) {
        return documentRegistryRepository.findById(documentRegistryId).orElse(null);
    }

    public record EvidenceChainEntry(Evidence evidence, DocumentRegistry document) {}

    public List<EvidenceChainEntry> getChainForEntity(UUID entityId) {
        return evidenceRepository.findByEntityId(entityId).stream()
                .map(ev -> new EvidenceChainEntry(ev,
                        ev.getDocumentRegistryId() != null
                                ? getDocument(ev.getDocumentRegistryId())
                                : null))
                .toList();
    }
}
