package com.meridian.substrate.engine.ai.generative;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.engine.ai.assertion.AssertionService;
import com.meridian.substrate.engine.ai.orchestration.AiMode;
import com.meridian.substrate.engine.ai.orchestration.OrchestratorResult;
import com.meridian.substrate.engine.ai.orchestration.SurfaceContext;
import com.meridian.substrate.engine.ai.orchestration.ToolCallingOrchestrator;
import com.meridian.substrate.engine.ai.persona.PersonaContext;
import com.meridian.substrate.common.repo.EvidenceRepository;
import com.meridian.substrate.common.domain.Assertion;
import com.meridian.substrate.common.domain.Evidence;
import com.meridian.substrate.common.domain.enums.ProducedBy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;



/**
 * Generative AI mode: produce DRAFT entities grounded in Universe Evidence,
 * never auto-persist them.
 *
 * <p>Architectural contract (principle P6 enforced at runtime):
 * <ul>
 *   <li>The LLM is instructed to return a JSON array of proposals, each
 *       carrying {@code evidenceIds}.</li>
 *   <li>Every proposal without grounding evidence is filtered — not silently
 *       included. Every {@code evidenceId} is verified to exist as an
 *       {@code Evidence} record in the requested domain; references that
 *       resolve outside the domain are dropped from the proposal's grounding,
 *       and proposals that lose all grounding this way are filtered.</li>
 *   <li>Surviving proposals are recorded as {@link Assertion}s with
 *       {@link ProducedBy#AI_GENERATIVE}; the entities themselves are NOT
 *       persisted — Phase 2B's Capability Designer commit step does that.</li>
 *   <li>If every candidate is filtered, the response carries an explanation
 *       and {@code drafts == []} — the UI must remain functional with no AI
 *       output.</li>
 * </ul>
 */
@Service
public class GenerativeModeService {

    private static final Logger log = LoggerFactory.getLogger(GenerativeModeService.class);

    /**
     * Allowlist of user-editable, AI-proposable fields per entity type. The set
     * for each type is derived deliberately from the entity's schema in the
     * redesign spec (Capability: doc 04 lines 19–34) — NOT from whatever the
     * stub LLM happens to emit in tests.
     *
     * <p>An allowlist (rather than a denylist of known meta keys) is the
     * structurally correct enforcement of principle P6 here: any future field
     * the LLM invents — {@code reasoning}, {@code rationale}, {@code category},
     * {@code notes}, etc. — is silently dropped without anyone needing to add
     * it to a list of "things to remove." Only fields the schema actually owns
     * survive into {@code proposedFields}.
     *
     * <p>Excluded from the Capability allowlist by deliberate design:
     * <ul>
     *   <li>{@code id}, {@code domainId}, {@code version}, {@code createdAt},
     *       {@code updatedAt}, {@code createdBy} — system-managed via
     *       {@code BaseEntity}, not user-proposable.</li>
     *   <li>{@code level} — derived from {@code parentId} per doc 04 line 27.</li>
     *   <li>{@code status} — doc 04 line 93 mandates "Generated tree opens in
     *       DRAFT status"; the AI does not choose status.</li>
     *   <li>{@code sourcePackId} — meaningful only when forking from a DemoPack,
     *       not in the generative path.</li>
     *   <li>{@code aiGenerated} — set by the commit step on the persisted
     *       entity, not an AI claim.</li>
     *   <li>{@code metadata} — opaque JSON column; not part of the user-editable
     *       proposal surface.</li>
     * </ul>
     *
     * <p>Field names follow the spec (doc 04 uses {@code parentId}); the JPA
     * entity uses {@code parentCapabilityId}. The Phase 2B commit step is
     * responsible for the rename when materializing a draft as a real
     * {@code Capability}.
     *
     * <p>Entity types not present in this map are rejected by
     * {@link #generate} — the service has no allowlist to enforce, so no
     * proposal can be safely returned.
     */
    private static final Map<String, Set<String>> ALLOWED_FIELDS_BY_ENTITY_TYPE = Map.of(
            "capability", Set.of("name", "displayName", "description", "parentId")
            // Other entity types (process, system, …) will be added when those
            // types ship through the generative path. Unknown types are rejected.
    );

    /**
     * Read-only view of the proposed-field allowlist for an entity type, or
     * {@code null} when the type has no allowlist (and {@link #generate}
     * would reject it). ADDITIVE accessor only (engine-spec diagnostic.md
     * §13): the eval harness asserts generative filter outcomes against THIS
     * allowlist instead of re-deriving its own copy — the allowlist semantics
     * themselves are phase2a-pinned and unchanged.
     */
    public static Set<String> allowedFieldsForEntityType(String entityType) {
        Set<String> allowed = ALLOWED_FIELDS_BY_ENTITY_TYPE.get(entityType);
        return allowed == null ? null : Set.copyOf(allowed);
    }

    private final ToolCallingOrchestrator orchestrator;
    private final AssertionService assertionService;
    private final EvidenceRepository evidenceRepository;
    private final ObjectMapper mapper;

    public GenerativeModeService(ToolCallingOrchestrator orchestrator,
                                 AssertionService assertionService,
                                 EvidenceRepository evidenceRepository,
                                 ObjectMapper mapper) {
        this.orchestrator = orchestrator;
        this.assertionService = assertionService;
        this.evidenceRepository = evidenceRepository;
        this.mapper = mapper;
    }

    public GenerateResponse generate(GenerateRequest request, PersonaContext persona) {
        log.info("Generative request [domainId={}, surface={}, entityType={}, readers={}]",
                request.domainId(), request.surface(), request.entityType(),
                request.readerIds() == null ? 0 : request.readerIds().size());

        Set<String> allowedFields = ALLOWED_FIELDS_BY_ENTITY_TYPE.get(request.entityType());
        if (allowedFields == null) {
            log.warn("Generative request rejected: no allowlist defined for entityType={}",
                    request.entityType());
            return new GenerateResponse(List.of(), 0, String.format(
                    "No proposed-field allowlist is defined for entity type '%s'; "
                            + "request rejected to avoid returning unvalidated fields.",
                    request.entityType()));
        }

        SurfaceContext surfaceContext = SurfaceContext.of(request.surface());
        String userMessage = buildUserMessage(request, allowedFields);

        OrchestratorResult result = orchestrator.orchestrate(
                userMessage, persona, request.domainId(), AiMode.GENERATIVE, surfaceContext);

        List<JsonNode> rawCandidates = parseProposals(result.finalText());
        if (rawCandidates.isEmpty()) {
            log.info("Generative produced no parseable proposals");
            return new GenerateResponse(List.of(), 0,
                    "AI did not return any structured proposals.");
        }

        Set<UUID> domainEvidenceIds = evidenceRepository.findByDomainId(request.domainId())
                .stream()
                .map(Evidence::getId)
                .collect(Collectors.toSet());

        List<DraftEntity> drafts = new ArrayList<>();
        int filtered = 0;

        for (JsonNode candidate : rawCandidates) {
            List<UUID> claimedEvidence = extractEvidenceIds(candidate);
            List<UUID> grounded = claimedEvidence.stream()
                    .filter(domainEvidenceIds::contains)
                    .toList();

            if (grounded.isEmpty()) {
                filtered++;
                log.debug("Filtered ungrounded proposal: claimed={}, grounded={}",
                        claimedEvidence.size(), 0);
                continue;
            }

            JsonNode proposedFields = extractAllowedFields(candidate, allowedFields);
            if (proposedFields.isEmpty()) {
                filtered++;
                log.debug("Filtered proposal with no allowlisted attributes (entityType={})",
                        request.entityType());
                continue;
            }

            JsonNode producedByContext = buildProducedByContext(request);

            Assertion assertion = assertionService.record(
                    request.domainId(),
                    "draft:" + request.entityType(),
                    "proposes_creation",
                    proposedFields.toString(),
                    candidate.has("confidence") ? (float) candidate.get("confidence").asDouble() : null,
                    grounded,
                    ProducedBy.AI_GENERATIVE,
                    producedByContext);

            drafts.add(new DraftEntity(
                    request.entityType(),
                    proposedFields,
                    grounded,
                    assertion.getId()));
        }

        log.info("Generative produced [drafts={}, filtered={}]", drafts.size(), filtered);

        String explanation = drafts.isEmpty()
                ? String.format(
                        "AI returned %d candidate(s); all were filtered (grounding or allowlist).",
                        rawCandidates.size())
                : null;

        return new GenerateResponse(drafts, filtered, explanation);
    }

    private String buildUserMessage(GenerateRequest request, Set<String> allowedFields) {
        StringBuilder sb = new StringBuilder();
        sb.append("Generate ").append(request.entityType())
                .append(" entities for domain ").append(request.domainId())
                .append(" using the Universe of Inputs as evidence.\n\n");
        if (request.readerIds() != null && !request.readerIds().isEmpty()) {
            sb.append("Restrict grounding to these readers: ")
                    .append(request.readerIds()).append("\n\n");
        }
        sb.append("Respond with ONLY a JSON array of candidate objects. Each candidate object MUST contain:\n");
        sb.append("  - evidenceIds: array of UUID strings referencing Evidence records that ground this proposal\n");
        sb.append("Allowed entity attribute fields (any subset, no others): ")
                .append(allowedFields).append("\n");
        sb.append("Optional metadata: confidence (0.0–1.0).\n\n");
        sb.append("Any field outside the allowed-attributes list will be silently dropped. ");
        sb.append("Do not propose entities without supporting evidence — they will be filtered out. ");
        sb.append("Use the find_evidence tool to discover citable evidence ids in this domain; ");
        sb.append("proposals citing evidence ids you did not retrieve will be filtered. ");
        sb.append("Do not include prose around the JSON array.");
        return sb.toString();
    }

    /**
     * Extract the first JSON array found in the LLM's final text. Tolerant of
     * surrounding prose since real models occasionally violate the
     * "no prose" instruction.
     */
    private List<JsonNode> parseProposals(String finalText) {
        if (finalText == null || finalText.isBlank()) {
            return List.of();
        }
        int start = finalText.indexOf('[');
        int end = finalText.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();
        }
        String jsonSlice = finalText.substring(start, end + 1);
        try {
            JsonNode parsed = mapper.readTree(jsonSlice);
            if (!parsed.isArray()) {
                return List.of();
            }
            List<JsonNode> out = new ArrayList<>();
            parsed.forEach(out::add);
            return out;
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse proposals JSON: {}", e.getMessage());
            return List.of();
        }
    }

    private List<UUID> extractEvidenceIds(JsonNode candidate) {
        JsonNode arr = candidate.get("evidenceIds");
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        Set<UUID> seen = new HashSet<>();
        List<UUID> out = new ArrayList<>();
        for (JsonNode node : arr) {
            if (!node.isTextual()) continue;
            try {
                UUID id = UUID.fromString(node.asText());
                if (seen.add(id)) {
                    out.add(id);
                }
            } catch (IllegalArgumentException e) {
                // skip non-UUID strings rather than failing the whole candidate
            }
        }
        return out;
    }

    /**
     * Build {@code proposedFields} by copying ONLY allowlisted keys from the
     * raw candidate. Any field the LLM emits outside the allowlist —
     * {@code reasoning}, {@code rationale}, {@code confidence},
     * {@code evidenceIds}, anything else — is silently dropped here, by
     * construction. Compare to a denylist approach, which would only catch
     * keys someone remembered to add to the deny set.
     */
    private JsonNode extractAllowedFields(JsonNode candidate, Set<String> allowedFields) {
        ObjectNode out = mapper.createObjectNode();
        for (String key : allowedFields) {
            JsonNode value = candidate.get(key);
            if (value != null && !value.isNull()) {
                out.set(key, value);
            }
        }
        return out;
    }

    private JsonNode buildProducedByContext(GenerateRequest request) {
        ObjectNode ctx = mapper.createObjectNode();
        ctx.put("mode", AiMode.GENERATIVE.name());
        ctx.put("surface", request.surface().name());
        ctx.put("entityType", request.entityType());
        return ctx;
    }
}
