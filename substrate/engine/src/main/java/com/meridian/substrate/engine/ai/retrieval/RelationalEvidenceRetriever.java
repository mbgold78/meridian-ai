package com.meridian.substrate.engine.ai.retrieval;

import com.meridian.substrate.common.domain.CanonicalEntity;
import com.meridian.substrate.common.domain.DesignDecision;
import com.meridian.substrate.common.domain.EntityOverlap;
import com.meridian.substrate.common.domain.Evidence;
import com.meridian.substrate.common.domain.Finding;
import com.meridian.substrate.common.domain.Integration;
import com.meridian.substrate.common.domain.OrganizationMember;
import com.meridian.substrate.common.domain.Process;
import com.meridian.substrate.common.domain.SystemEntity;
import com.meridian.substrate.common.repo.CanonicalEntityRepository;
import com.meridian.substrate.common.repo.DesignDecisionRepository;
import com.meridian.substrate.common.repo.EntityOverlapRepository;
import com.meridian.substrate.common.repo.EvidenceRepository;
import com.meridian.substrate.common.repo.FindingRepository;
import com.meridian.substrate.common.repo.IntegrationRepository;
import com.meridian.substrate.common.repo.OrganizationMemberRepository;
import com.meridian.substrate.common.repo.ProcessRepository;
import com.meridian.substrate.common.repo.SystemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Relational-store implementation of {@link EvidenceRetriever} that queries
 * JPA repositories based on the {@link EvidenceQueryType}. All results receive
 * a relevance score of 1.0 because relational lookups are exact matches.
 *
 * <p><b>Port note (port-map §2.3 / dead-code mirage):</b> homepoc shipped this
 * class with zero callers. In substrate it is a live code path:
 * {@code AssistiveModeService} assembles its grounding/citation pool through
 * the {@link EvidenceQueryType#EVIDENCE} branch, making this retriever the
 * evidence-assembly seam where a future vector/document retriever plugs in.
 */
@Service
@Primary
public class RelationalEvidenceRetriever implements EvidenceRetriever {

    private static final Logger log = LoggerFactory.getLogger(RelationalEvidenceRetriever.class);

    private final SystemRepository systemRepository;
    private final CanonicalEntityRepository canonicalEntityRepository;
    private final FindingRepository findingRepository;
    private final IntegrationRepository integrationRepository;
    private final EntityOverlapRepository entityOverlapRepository;
    private final OrganizationMemberRepository organizationMemberRepository;
    private final ProcessRepository processRepository;
    private final DesignDecisionRepository designDecisionRepository;
    private final EvidenceRepository evidenceRepository;

    public RelationalEvidenceRetriever(SystemRepository systemRepository,
                                       CanonicalEntityRepository canonicalEntityRepository,
                                       FindingRepository findingRepository,
                                       IntegrationRepository integrationRepository,
                                       EntityOverlapRepository entityOverlapRepository,
                                       OrganizationMemberRepository organizationMemberRepository,
                                       ProcessRepository processRepository,
                                       DesignDecisionRepository designDecisionRepository,
                                       EvidenceRepository evidenceRepository) {
        this.systemRepository = systemRepository;
        this.canonicalEntityRepository = canonicalEntityRepository;
        this.findingRepository = findingRepository;
        this.integrationRepository = integrationRepository;
        this.entityOverlapRepository = entityOverlapRepository;
        this.organizationMemberRepository = organizationMemberRepository;
        this.processRepository = processRepository;
        this.designDecisionRepository = designDecisionRepository;
        this.evidenceRepository = evidenceRepository;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Dispatches to the appropriate repository based on the query type, applies
     * domain-level filters, converts entities to {@link EvidenceChunk} instances,
     * and applies the result limit.
     */
    @Override
    public List<EvidenceChunk> retrieve(EvidenceQuery query) {
        log.debug("Retrieving evidence [type={}, filters={}, limit={}]",
                query.type(), query.filters(), query.limit());

        UUID domainId = extractDomainId(query.filters());

        Stream<EvidenceChunk> chunks = switch (query.type()) {
            case SYSTEMS -> retrieveSystems(domainId);
            case ENTITIES -> retrieveEntities(domainId);
            case FINDINGS -> retrieveFindings(domainId);
            case INTEGRATIONS -> retrieveIntegrations(domainId);
            case OVERLAPS -> retrieveOverlaps(domainId);
            case ORGANIZATION -> retrieveOrganization(domainId);
            case PROCESSES -> retrieveProcesses(domainId);
            case DECISIONS -> retrieveDecisions(domainId);
            case EVIDENCE -> retrieveEvidenceRecords(domainId);
        };

        List<EvidenceChunk> result = query.limit() != null
                ? chunks.limit(query.limit()).collect(Collectors.toList())
                : chunks.collect(Collectors.toList());

        log.debug("Retrieved {} evidence chunks for type {}", result.size(), query.type());
        return result;
    }

    // -------------------------------------------------------------------------
    // Per-type retrieval methods
    // -------------------------------------------------------------------------

    private Stream<EvidenceChunk> retrieveSystems(UUID domainId) {
        List<SystemEntity> systems = domainId != null
                ? systemRepository.findByDomainId(domainId)
                : systemRepository.findAll();

        return systems.stream().map(s -> new EvidenceChunk(
                String.format("System: %s | Vendor: %s | Type: %s | Status: %s | Deployed: %s | Description: %s",
                        s.getName(),
                        orUnknown(s.getVendor()),
                        orUnknown(s.getSystemType()),
                        orUnknown(s.getStatus()),
                        s.getDeployedYear() != null ? s.getDeployedYear().toString() : "unknown",
                        orUnknown(s.getDescription())),
                new EvidenceSource(EvidenceSourceType.SYSTEM,
                        s.getId().toString(), s.getName(), null),
                1.0,
                Map.of("systemType", orEmpty(s.getSystemType()),
                       "businessLine", orEmpty(s.getBusinessLine()))
        ));
    }

    private Stream<EvidenceChunk> retrieveEntities(UUID domainId) {
        List<CanonicalEntity> entities = domainId != null
                ? canonicalEntityRepository.findByDomainId(domainId)
                : canonicalEntityRepository.findAll();

        return entities.stream().map(e -> new EvidenceChunk(
                String.format("Entity: %s | Type: %s | Execution Mode: %s | Description: %s",
                        e.getName(),
                        orUnknown(e.getEntityType()),
                        e.getExecutionMode() != null ? e.getExecutionMode().name() : "unknown",
                        orUnknown(e.getDescription())),
                new EvidenceSource(EvidenceSourceType.CANONICAL_ENTITY,
                        e.getId().toString(), e.getName(), null),
                1.0,
                Map.of("entityType", orEmpty(e.getEntityType()))
        ));
    }

    private Stream<EvidenceChunk> retrieveFindings(UUID domainId) {
        List<Finding> findings = domainId != null
                ? findingRepository.findByDomainId(domainId)
                : findingRepository.findAll();

        return findings.stream().map(f -> new EvidenceChunk(
                String.format("Finding: %s | Type: %s | Severity: %s | Status: %s | Description: %s | Recommendation: %s",
                        f.getTitle(),
                        f.getFindingType() != null ? f.getFindingType().name() : "unknown",
                        f.getSeverity() != null ? f.getSeverity().name() : "unknown",
                        f.getStatus() != null ? f.getStatus().name() : "unknown",
                        orUnknown(f.getDescription()),
                        orUnknown(f.getRecommendation())),
                new EvidenceSource(EvidenceSourceType.FINDING,
                        f.getId().toString(), f.getTitle(), null),
                1.0,
                Map.of("findingType", f.getFindingType() != null ? f.getFindingType().name() : "",
                       "severity", f.getSeverity() != null ? f.getSeverity().name() : "")
        ));
    }

    private Stream<EvidenceChunk> retrieveIntegrations(UUID domainId) {
        List<Integration> integrations = domainId != null
                ? integrationRepository.findByDomainId(domainId)
                : integrationRepository.findAll();

        return integrations.stream().map(i -> new EvidenceChunk(
                String.format("Integration: %s | Type: %s | Status: %s | Frequency: %s | Description: %s",
                        orUnknown(i.getName()),
                        i.getIntegrationType() != null ? i.getIntegrationType().name() : "unknown",
                        i.getIntegrationStatus() != null ? i.getIntegrationStatus().name() : "unknown",
                        orUnknown(i.getFrequency()),
                        orUnknown(i.getDescription())),
                new EvidenceSource(EvidenceSourceType.INTEGRATION,
                        i.getId().toString(),
                        i.getName() != null ? i.getName() : "Integration " + i.getId(),
                        null),
                1.0,
                Map.of("integrationType", i.getIntegrationType() != null ? i.getIntegrationType().name() : "")
        ));
    }

    private Stream<EvidenceChunk> retrieveOverlaps(UUID domainId) {
        List<EntityOverlap> overlaps = domainId != null
                ? entityOverlapRepository.findByDomainId(domainId)
                : entityOverlapRepository.findAll();

        return overlaps.stream().map(o -> new EvidenceChunk(
                String.format("Overlap: source=%s, target=%s | Type: %s | Confidence: %.2f | AI Inferred: %s",
                        o.getSourceEntityId(),
                        o.getTargetEntityId(),
                        orUnknown(o.getOverlapType()),
                        o.getConfidence() != null ? o.getConfidence() : 0.0,
                        o.getAiInferred() != null ? o.getAiInferred().toString() : "unknown"),
                new EvidenceSource(EvidenceSourceType.CANONICAL_ENTITY,
                        o.getId().toString(),
                        "Overlap " + o.getOverlapType(),
                        null),
                1.0,
                Map.of("overlapType", orEmpty(o.getOverlapType()),
                       "confidence", o.getConfidence() != null ? o.getConfidence() : 0.0)
        ));
    }

    private Stream<EvidenceChunk> retrieveOrganization(UUID domainId) {
        List<OrganizationMember> members = domainId != null
                ? organizationMemberRepository.findByDomainId(domainId)
                : organizationMemberRepository.findAll();

        return members.stream().map(m -> new EvidenceChunk(
                String.format("Organization Member: %s | Title: %s | Department: %s | Business Line: %s | Reports To: %s",
                        m.getName(),
                        orUnknown(m.getTitle()),
                        orUnknown(m.getDepartment()),
                        orUnknown(m.getBusinessLine()),
                        orUnknown(m.getReportsTo())),
                new EvidenceSource(EvidenceSourceType.ORGANIZATION_MEMBER,
                        m.getId().toString(), m.getName(), null),
                1.0,
                Map.of("department", orEmpty(m.getDepartment()),
                       "businessLine", orEmpty(m.getBusinessLine()))
        ));
    }

    private Stream<EvidenceChunk> retrieveProcesses(UUID domainId) {
        List<Process> processes = domainId != null
                ? processRepository.findByDomainId(domainId)
                : processRepository.findAll();

        return processes.stream().map(p -> new EvidenceChunk(
                String.format("Process: %s | Classification: %s | Frequency: %s | Owner: %s | Risk: %s | FTE: %s | Documentation: %s | Description: %s",
                        p.getName(),
                        p.getClassification() != null ? p.getClassification().name() : "unknown",
                        orUnknown(p.getFrequency()),
                        orUnknown(p.getPrimaryOwner()),
                        p.getRiskRating() != null ? p.getRiskRating().name() : "unknown",
                        p.getEstimatedFte() != null ? String.format("%.1f", p.getEstimatedFte()) : "unknown",
                        p.getDocumentationQuality() != null ? p.getDocumentationQuality().name() : "unknown",
                        orUnknown(p.getDescription())),
                new EvidenceSource(EvidenceSourceType.PROCESS,
                        p.getId().toString(), p.getName(), null),
                1.0,
                Map.of("classification", p.getClassification() != null ? p.getClassification().name() : "",
                       "riskRating", p.getRiskRating() != null ? p.getRiskRating().name() : "")
        ));
    }

    private Stream<EvidenceChunk> retrieveDecisions(UUID domainId) {
        List<DesignDecision> decisions = domainId != null
                ? designDecisionRepository.findByDomainId(domainId)
                : designDecisionRepository.findAll();

        return decisions.stream().map(d -> new EvidenceChunk(
                String.format("Decision: %s | Type: %s | Status: %s | Rationale: %s | Description: %s",
                        d.getTitle(),
                        d.getDecisionType() != null ? d.getDecisionType().name() : "unknown",
                        d.getStatus() != null ? d.getStatus().name() : "unknown",
                        orUnknown(d.getRationale()),
                        orUnknown(d.getDescription())),
                new EvidenceSource(EvidenceSourceType.DESIGN_DECISION,
                        d.getId().toString(), d.getTitle(), null),
                1.0,
                Map.of("decisionType", d.getDecisionType() != null ? d.getDecisionType().name() : "",
                       "status", d.getStatus() != null ? d.getStatus().name() : "")
        ));
    }

    /**
     * NEW in substrate: chunks sourced from the {@code evidence} table itself —
     * the P6 grounding records. {@code source.sourceId} is the Evidence row UUID
     * (the ID mode services list as citable and validate grounding against);
     * {@code source.displayName} carries the Evidence label.
     */
    private Stream<EvidenceChunk> retrieveEvidenceRecords(UUID domainId) {
        List<Evidence> records = domainId != null
                ? evidenceRepository.findByDomainId(domainId)
                : evidenceRepository.findAll();

        return records.stream().map(e -> new EvidenceChunk(
                String.format("Evidence: %s | Entity: %s:%s | Source File: %s | Extractor: %s",
                        orUnknown(e.getLabel()),
                        orUnknown(e.getEntityType()),
                        e.getEntityId() != null ? e.getEntityId() : "unknown",
                        orUnknown(e.getSourceFile()),
                        orUnknown(e.getExtractor())),
                new EvidenceSource(EvidenceSourceType.EVIDENCE_RECORD,
                        e.getId().toString(),
                        e.getLabel(),
                        null),
                1.0,
                Map.of("entityType", orEmpty(e.getEntityType()),
                       "confidence", e.getConfidence() != null ? e.getConfidence() : 0.0)
        ));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UUID extractDomainId(Map<String, Object> filters) {
        if (filters == null) {
            return null;
        }
        Object value = filters.get("domainId");
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof String s) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid domainId filter value: {}", s);
                return null;
            }
        }
        return null;
    }

    private static String orUnknown(String value) {
        return value != null && !value.isBlank() ? value : "unknown";
    }

    private static String orEmpty(String value) {
        return value != null ? value : "";
    }
}
