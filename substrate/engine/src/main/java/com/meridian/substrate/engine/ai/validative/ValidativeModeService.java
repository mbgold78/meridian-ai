package com.meridian.substrate.engine.ai.validative;

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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Validative AI mode: audit the existing model and produce structured
 * {@link FindingCandidate}s. Findings are returned as DTOs — never auto-persisted
 * as {@code Finding} entities (the caller decides whether to surface or commit).
 *
 * <p>Architectural contract:
 * <ul>
 *   <li><b>Type allowlist.</b> Only {@link FindingCandidateType#STRUCTURAL} and
 *       {@link FindingCandidateType#CONTENT} are accepted; any other value
 *       filters the candidate.</li>
 *   <li><b>Severity allowlist.</b> Only {@link FindingCandidateSeverity}
 *       (CRITICAL / HIGH / MEDIUM / INFO) is accepted; any other value
 *       (e.g. {@code LOW} from the legacy enum, or LLM hallucinations like
 *       "URGENT") filters the candidate.</li>
 *   <li><b>Required fields.</b> {@code description} and {@code suggestedAction}
 *       must be non-blank; otherwise the candidate is filtered.</li>
 *   <li><b>Differential grounding (P6 enforced at runtime).</b>
 *       {@link FindingCandidateType#CONTENT} findings must cite ≥1
 *       {@code evidenceId} that resolves to an {@code Evidence} row in the
 *       requested domain — claimed evidence outside the domain or referencing
 *       nonexistent IDs is dropped, and a CONTENT finding that loses all
 *       grounding this way is filtered. {@link FindingCandidateType#STRUCTURAL}
 *       findings are accepted with or without evidence (model-state observations
 *       are self-justifying).</li>
 *   <li><b>Assertion provenance.</b> Each surviving candidate is persisted as an
 *       {@code Assertion} with {@link ProducedBy#AI_VALIDATIVE} so a future
 *       audit can trace what the AI claimed at validation time.</li>
 * </ul>
 */
@Service
public class ValidativeModeService {

    private static final Logger log = LoggerFactory.getLogger(ValidativeModeService.class);

    private final ToolCallingOrchestrator orchestrator;
    private final AssertionService assertionService;
    private final EvidenceRepository evidenceRepository;
    private final ObjectMapper mapper;

    public ValidativeModeService(ToolCallingOrchestrator orchestrator,
                                 AssertionService assertionService,
                                 EvidenceRepository evidenceRepository,
                                 ObjectMapper mapper) {
        this.orchestrator = orchestrator;
        this.assertionService = assertionService;
        this.evidenceRepository = evidenceRepository;
        this.mapper = mapper;
    }

    public ValidateResponse validate(ValidateRequest request, PersonaContext persona) {
        log.info("Validative request [domainId={}, surface={}, scope={}, targets={}]",
                request.domainId(), request.surface(), request.scope(),
                request.targetEntityIds() == null ? 0 : request.targetEntityIds().size());

        SurfaceContext surfaceContext = SurfaceContext.of(request.surface());
        String userMessage = buildUserMessage(request);

        OrchestratorResult result = orchestrator.orchestrate(
                userMessage, persona, request.domainId(), AiMode.VALIDATIVE, surfaceContext);

        List<JsonNode> rawFindings = parseFindings(result.finalText());
        if (rawFindings.isEmpty()) {
            log.info("Validative produced no parseable findings");
            return new ValidateResponse(List.of(), 0,
                    "AI did not return any structured findings.");
        }

        Set<UUID> domainEvidenceIds = evidenceRepository.findByDomainId(request.domainId())
                .stream()
                .map(Evidence::getId)
                .collect(Collectors.toSet());

        List<FindingCandidate> kept = new ArrayList<>();
        int filtered = 0;

        for (JsonNode raw : rawFindings) {
            FindingCandidate candidate = validateAndBuild(raw, domainEvidenceIds, request);
            if (candidate == null) {
                filtered++;
                continue;
            }
            kept.add(candidate);
        }

        log.info("Validative produced [findings={}, filtered={}]", kept.size(), filtered);

        String explanation = kept.isEmpty()
                ? String.format(
                        "AI returned %d finding(s); all were filtered (type/severity allowlist, missing required fields, or unmet grounding requirement).",
                        rawFindings.size())
                : null;

        return new ValidateResponse(kept, filtered, explanation);
    }

    /**
     * Validate every required field, enforce both allowlists and the
     * differential grounding rule, then persist an Assertion and return the
     * candidate. Returns {@code null} if any check fails — the caller increments
     * the filtered count.
     */
    private FindingCandidate validateAndBuild(JsonNode raw,
                                              Set<UUID> domainEvidenceIds,
                                              ValidateRequest request) {
        FindingCandidateType type = parseType(raw);
        if (type == null) {
            log.debug("Filtered finding: invalid or missing 'type' (must be STRUCTURAL or CONTENT)");
            return null;
        }

        FindingCandidateSeverity severity = parseSeverity(raw);
        if (severity == null) {
            log.debug("Filtered finding: invalid or missing 'severity' (must be one of {})",
                    Arrays.toString(FindingCandidateSeverity.values()));
            return null;
        }

        String description = textOrNull(raw, "description");
        String suggestedAction = textOrNull(raw, "suggestedAction");
        if (description == null || description.isBlank()
                || suggestedAction == null || suggestedAction.isBlank()) {
            log.debug("Filtered finding: missing required field (description or suggestedAction)");
            return null;
        }

        String affectedEntityType = textOrNull(raw, "affectedEntityType");
        UUID affectedEntityId = parseUuidOrNull(raw, "affectedEntityId");

        List<UUID> claimedEvidence = extractEvidenceIds(raw);
        List<UUID> grounded = claimedEvidence.stream()
                .filter(domainEvidenceIds::contains)
                .toList();

        // Differential grounding rule: CONTENT requires ≥1 grounded evidence;
        // STRUCTURAL accepts any (or none).
        if (type == FindingCandidateType.CONTENT && grounded.isEmpty()) {
            log.debug("Filtered CONTENT finding: claimed {} evidenceIds, {} grounded in domain",
                    claimedEvidence.size(), grounded.size());
            return null;
        }

        Assertion assertion = persistAssertion(
                request, type, severity, affectedEntityType, affectedEntityId,
                description, suggestedAction, grounded);

        return new FindingCandidate(
                type, severity, affectedEntityType, affectedEntityId,
                description, suggestedAction, grounded, assertion.getId());
    }

    private Assertion persistAssertion(ValidateRequest request,
                                       FindingCandidateType type,
                                       FindingCandidateSeverity severity,
                                       String affectedEntityType,
                                       UUID affectedEntityId,
                                       String description,
                                       String suggestedAction,
                                       List<UUID> grounded) {
        ObjectNode object = mapper.createObjectNode();
        object.put("severity", severity.name());
        object.put("description", description);
        object.put("suggestedAction", suggestedAction);
        if (affectedEntityType != null) object.put("affectedEntityType", affectedEntityType);
        if (affectedEntityId != null) object.put("affectedEntityId", affectedEntityId.toString());

        String subject = affectedEntityType != null && affectedEntityId != null
                ? affectedEntityType + ":" + affectedEntityId
                : "domain:" + request.domainId();

        ObjectNode producedByContext = mapper.createObjectNode();
        producedByContext.put("mode", AiMode.VALIDATIVE.name());
        producedByContext.put("surface", request.surface().name());
        producedByContext.put("scope", request.scope());

        return assertionService.record(
                request.domainId(),
                subject,
                "validative_finding:" + type.name(),
                object.toString(),
                null,
                grounded,
                ProducedBy.AI_VALIDATIVE,
                producedByContext);
    }

    private FindingCandidateType parseType(JsonNode raw) {
        String value = textOrNull(raw, "type");
        if (value == null) return null;
        return Stream.of(FindingCandidateType.values())
                .filter(t -> t.name().equals(value))
                .findFirst()
                .orElse(null);
    }

    private FindingCandidateSeverity parseSeverity(JsonNode raw) {
        String value = textOrNull(raw, "severity");
        if (value == null) return null;
        return Stream.of(FindingCandidateSeverity.values())
                .filter(s -> s.name().equals(value))
                .findFirst()
                .orElse(null);
    }

    private String textOrNull(JsonNode raw, String key) {
        JsonNode node = raw.get(key);
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private UUID parseUuidOrNull(JsonNode raw, String key) {
        String text = textOrNull(raw, key);
        if (text == null) return null;
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private List<UUID> extractEvidenceIds(JsonNode raw) {
        JsonNode arr = raw.get("evidenceIds");
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
            } catch (IllegalArgumentException ignored) {
                // skip non-UUID strings
            }
        }
        return out;
    }

    private List<JsonNode> parseFindings(String finalText) {
        if (finalText == null || finalText.isBlank()) {
            return List.of();
        }
        int start = finalText.indexOf('[');
        int end = finalText.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();
        }
        try {
            JsonNode parsed = mapper.readTree(finalText.substring(start, end + 1));
            if (!parsed.isArray()) return List.of();
            List<JsonNode> out = new ArrayList<>();
            parsed.forEach(out::add);
            return out;
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse validative findings JSON: {}", e.getMessage());
            return List.of();
        }
    }

    private String buildUserMessage(ValidateRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("Audit the ").append(request.scope())
                .append(" entities in domain ").append(request.domainId())
                .append(" for gaps, contradictions, drift, or quality issues.\n\n");
        if (request.targetEntityIds() != null && !request.targetEntityIds().isEmpty()) {
            sb.append("Restrict to these entity IDs: ").append(request.targetEntityIds()).append("\n\n");
        }
        sb.append("Respond with ONLY a JSON array of finding objects. Each finding object MUST include:\n");
        sb.append("  - type: \"STRUCTURAL\" (model-state observation, e.g. \"capability has no processes mapped\") OR ");
        sb.append("\"CONTENT\" (claim comparing model to source material, e.g. \"description contradicts policy doc\")\n");
        sb.append("  - severity: one of ").append(Arrays.toString(FindingCandidateSeverity.values())).append("\n");
        sb.append("  - description: what is wrong\n");
        sb.append("  - suggestedAction: what to do about it\n");
        sb.append("Optional: affectedEntityType, affectedEntityId (UUID).\n");
        sb.append("evidenceIds: array of UUID strings — REQUIRED for CONTENT findings, optional for STRUCTURAL.\n\n");
        sb.append("Findings will be filtered if: type or severity is outside the allowed set; ");
        sb.append("description or suggestedAction is missing; or a CONTENT finding lacks grounding evidence in this domain. ");
        sb.append("Do not include prose around the JSON array.");
        return sb.toString();
    }
}
