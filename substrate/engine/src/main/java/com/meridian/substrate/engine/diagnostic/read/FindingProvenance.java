package com.meridian.substrate.engine.diagnostic.read;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.AiCurationEvent;
import com.meridian.substrate.common.domain.Assertion;
import com.meridian.substrate.common.domain.DocumentRegistry;
import com.meridian.substrate.common.domain.Evidence;
import com.meridian.substrate.common.domain.Finding;
import com.meridian.substrate.common.domain.IngestRun;
import com.meridian.substrate.common.domain.ReaderRegistration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The full P6 audit walk for one finding, materialized in one response
 * (engine-spec diagnostic.md §6.1; ADR-004 D5): finding (incl. curation
 * projection) → assertion → evidence[] → documentRegistry[] → ingestRuns[] →
 * readerRegistrations[] (when a registry row traces to one) → curationEvents[]
 * (the human decisions, in order).
 *
 * <p>{@code chainComplete} is {@code false} on ANY dangling hop — surfaced in
 * {@code missingHops} by name, never elided. {@code groundingMode} is the
 * persisted honesty marker ({@code EXISTENCE_ONLY} for all v1 AI findings —
 * spec §6.2). {@code fixtureGrounded} flags any walked evidence hop with
 * {@code extractor='seed'} (DemoPack provenance, distinct from real ingestion
 * chains). {@code evidenceSource} names which lane produced the evidence hop:
 * {@code ASSERTION} (the P6 chain), {@code ENTITY_LINKED} (operator/fixture
 * findings with no assertion — evidence attached to the finding or its
 * affected entities), or {@code NONE}.
 */
public record FindingProvenance(
        FindingView finding,
        AssertionView assertion,
        List<EvidenceView> evidence,
        List<DocumentRegistryView> documentRegistry,
        List<IngestRunView> ingestRuns,
        List<ReaderRegistrationView> readerRegistrations,
        List<CurationEventView> curationEvents,
        boolean chainComplete,
        List<String> missingHops,
        String groundingMode,
        boolean fixtureGrounded,
        String evidenceSource
) {

    public static final String SOURCE_ASSERTION = "ASSERTION";
    public static final String SOURCE_ENTITY_LINKED = "ENTITY_LINKED";
    public static final String SOURCE_NONE = "NONE";

    /** Finding projection incl. the V7 curation columns and the enriched evidence snapshot. */
    public record FindingView(
            UUID id,
            UUID domainId,
            String title,
            String description,
            String findingType,
            String severity,
            String status,
            String recommendation,
            Boolean aiGenerated,
            UUID synthesisRunId,
            UUID assertionId,
            String curationStatus,
            Instant curatedAt,
            String curatedByLabel,
            JsonNode evidenceSnapshot,
            JsonNode metadata,
            Instant createdAt
    ) {
        public static FindingView of(Finding f) {
            return new FindingView(
                    f.getId(), f.getDomainId(), f.getTitle(), f.getDescription(),
                    f.getFindingType() != null ? f.getFindingType().name() : null,
                    f.getSeverity() != null ? f.getSeverity().name() : null,
                    f.getStatus() != null ? f.getStatus().name() : null,
                    f.getRecommendation(), f.getAiGenerated(), f.getSynthesisRunId(),
                    f.getAssertionId(),
                    f.getCurationStatus() != null ? f.getCurationStatus().name() : null,
                    f.getCuratedAt(), f.getCuratedByLabel(),
                    f.getEvidence(), f.getMetadata(), f.getCreatedAt());
        }
    }

    public record AssertionView(
            UUID id,
            String subject,
            String predicate,
            String object,
            Float confidence,
            String producedBy,
            JsonNode producedByContext,
            JsonNode evidenceIds
    ) {
        public static AssertionView of(Assertion a) {
            return new AssertionView(a.getId(), a.getSubject(), a.getPredicate(), a.getObject(),
                    a.getConfidence(),
                    a.getProducedBy() != null ? a.getProducedBy().name() : null,
                    a.getProducedByContext(), a.getEvidenceIds());
        }
    }

    public record EvidenceView(
            UUID id,
            UUID entityId,
            String entityType,
            String label,
            Float confidence,
            String sourceFile,
            String extractor,
            UUID documentRegistryId
    ) {
        public static EvidenceView of(Evidence e) {
            return new EvidenceView(e.getId(), e.getEntityId(), e.getEntityType(), e.getLabel(),
                    e.getConfidence(), e.getSourceFile(), e.getExtractor(), e.getDocumentRegistryId());
        }
    }

    public record DocumentRegistryView(
            UUID id,
            String sourceFile,
            String sha256,
            String ingestionStatus,
            UUID processedByConverterRunId
    ) {
        public static DocumentRegistryView of(DocumentRegistry d) {
            return new DocumentRegistryView(d.getId(), d.getSourceFile(), d.getSourceFileSha256(),
                    d.getIngestionStatus() != null ? d.getIngestionStatus().name() : null,
                    d.getProcessedByConverterRunId());
        }
    }

    public record IngestRunView(
            UUID id,
            UUID domainId,
            String command,
            String status,
            Instant startedAt,
            Instant finishedAt
    ) {
        public static IngestRunView of(IngestRun r) {
            return new IngestRunView(r.getId(), r.getDomainId(), r.getCommand(), r.getStatus(),
                    r.getStartedAt(), r.getFinishedAt());
        }
    }

    public record ReaderRegistrationView(
            UUID id,
            String kind,
            String name,
            String uri,
            String status
    ) {
        public static ReaderRegistrationView of(ReaderRegistration r) {
            return new ReaderRegistrationView(r.getId(),
                    r.getKind() != null ? r.getKind().name() : null,
                    r.getName(), r.getUri(),
                    r.getStatus() != null ? r.getStatus().name() : null);
        }
    }

    public record CurationEventView(
            UUID id,
            String action,
            String rationale,
            String actorLabel,
            String actorPersonaId,
            JsonNode overriddenFields,
            UUID createdEntityId,
            UUID synthesisRunId,
            String idempotencyKey,
            Instant createdAt
    ) {
        public static CurationEventView of(AiCurationEvent e) {
            return new CurationEventView(e.getId(),
                    e.getAction() != null ? e.getAction().name() : null,
                    e.getRationale(), e.getActorLabel(), e.getActorPersonaId(),
                    e.getOverriddenFields(), e.getCreatedEntityId(), e.getSynthesisRunId(),
                    e.getIdempotencyKey(), e.getCreatedAt());
        }
    }
}
