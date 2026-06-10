package com.meridian.substrate.app.demo;

import com.meridian.substrate.common.domain.DocumentRegistry;
import com.meridian.substrate.common.domain.Evidence;
import com.meridian.substrate.common.domain.enums.DocumentType;
import com.meridian.substrate.common.domain.enums.IngestionStatus;
import com.meridian.substrate.common.repo.DocumentRegistryRepository;
import com.meridian.substrate.common.repo.EvidenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Seeds provenance records for the other seeded entities: synthetic
 * {@link DocumentRegistry} entries representing the input JSON files, plus one
 * {@link Evidence} record per seeded system and seeded process pointing back
 * at those docs.
 *
 * <p>Stays its own bounded context rather than folding into {@link DiagnosticSeeder}
 * because the relationship is "claim → citation": findings/decisions are claims,
 * evidence is the substrate backing them. Evidence isn't diagnostic output.
 *
 * <p>Fixture-seam note (port-map §5 S3): these are synthetic demo provenance
 * rows (sha256 sentinels, extractor='seed'). Real Evidence is written by the
 * ingestion module (DocumentRegistry at convert time, Evidence per committed
 * entity, IngestRun provenance).
 */
@Component
public class EvidenceSeeder implements Seeder {

    private static final Logger log = LoggerFactory.getLogger(EvidenceSeeder.class);

    private final DocumentRegistryRepository documentRegistryRepository;
    private final EvidenceRepository evidenceRepository;

    public EvidenceSeeder(DocumentRegistryRepository documentRegistryRepository,
                           EvidenceRepository evidenceRepository) {
        this.documentRegistryRepository = documentRegistryRepository;
        this.evidenceRepository = evidenceRepository;
    }

    @Override
    public void seedBulk(SeedContext ctx) {
        UUID domainId = ctx.getDomainId();

        UUID sysDocId = registerDoc("systems.json", "seed-systems-sha256");
        UUID procDocId = registerDoc("processes.json", "seed-processes-sha256");

        List<Evidence> records = new ArrayList<>();
        for (Map.Entry<String, UUID> e : ctx.systemsByName().entrySet()) {
            records.add(buildEvidence(e.getValue(), "system", domainId, sysDocId,
                    "systems.json", "System inventory — " + e.getKey()));
        }
        for (Map.Entry<String, UUID> e : ctx.processesByName().entrySet()) {
            records.add(buildEvidence(e.getValue(), "process", domainId, procDocId,
                    "processes.json", "Process inventory — " + e.getKey()));
        }
        evidenceRepository.saveAll(records);

        log.info("[seed] EvidenceSeeder: 2 document registries, {} provenance records", records.size());
    }

    private UUID registerDoc(String sourceFile, String sha) {
        DocumentRegistry doc = new DocumentRegistry();
        doc.setSourceFile(sourceFile);
        doc.setSourceFileSha256(sha);
        doc.setDocumentType(DocumentType.SAMPLE_DATA);
        doc.setSourceSystem("seed");
        doc.setIngestedToTachyon(false);
        doc.setIngestionStatus(IngestionStatus.LOCAL_ONLY);
        doc.setCreatedBy("seed");
        return documentRegistryRepository.save(doc).getId();
    }

    private Evidence buildEvidence(UUID entityId, String entityType, UUID domainId,
                                    UUID docRegId, String sourceFile, String label) {
        Evidence ev = new Evidence();
        ev.setEntityId(entityId);
        ev.setEntityType(entityType);
        ev.setDomainId(domainId);
        ev.setDocumentRegistryId(docRegId);
        ev.setSourceFile(sourceFile);
        ev.setConfidence(1.0f);
        ev.setLabel(label);
        ev.setExtractor("seed");
        ev.setCreatedBy("seed");
        return ev;
    }

    @Override
    public List<Class<? extends Seeder>> dependsOn() {
        return List.of(DomainSeeder.class, SystemSeeder.class, ProcessSeeder.class);
    }
}
