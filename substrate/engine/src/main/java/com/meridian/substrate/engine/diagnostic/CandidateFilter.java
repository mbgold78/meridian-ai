package com.meridian.substrate.engine.diagnostic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.Assertion;
import com.meridian.substrate.common.domain.Evidence;
import com.meridian.substrate.common.domain.enums.ProducedBy;
import com.meridian.substrate.common.repo.EvidenceRepository;
import com.meridian.substrate.engine.ai.assertion.AssertionService;
import com.meridian.substrate.engine.ai.orchestration.AiMode;
import com.meridian.substrate.engine.ai.orchestration.Surface;
import com.meridian.substrate.engine.ai.validative.FindingCandidate;
import com.meridian.substrate.engine.ai.validative.FindingCandidateSeverity;
import com.meridian.substrate.engine.ai.validative.FindingCandidateType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * THE single grounding chokepoint (engine-spec diagnostic.md §7.2; ADR-004 D1).
 *
 * <p>The parse → type/severity-allowlist → required-fields → differential-
 * grounding → {@code AssertionService.record(AI_VALIDATIVE)} core extracted
 * VERBATIM from {@code ValidativeModeService.validateAndBuild} — behavior is
 * byte-identical by construction (the mode service now delegates here; the
 * pinned phase2a suites stay green unmodified). All candidates — in-process
 * or orchestrator-dispatched ({@code OrchestratorDiagnosticExecutor}, build
 * step 8) — transit this filter before any persistence. Agents are advisors,
 * never mutators: dispatched output re-enters substrate ONLY through this
 * exact code path.
 *
 * <p>Contract (pinned by the phase2a suites and {@code CandidateFilterTest}):
 * <ul>
 *   <li><b>Type allowlist.</b> Only STRUCTURAL and CONTENT are accepted;
 *       any other value → {@link Outcome#BAD_TYPE}.</li>
 *   <li><b>Severity allowlist.</b> Only CRITICAL / HIGH / MEDIUM / INFO;
 *       any other value (legacy {@code LOW}, hallucinations like
 *       {@code URGENT}) → {@link Outcome#BAD_SEVERITY}.</li>
 *   <li><b>Required fields.</b> {@code description} and
 *       {@code suggestedAction} must be non-blank →
 *       {@link Outcome#MISSING_FIELD}.</li>
 *   <li><b>Differential grounding (P6 at runtime).</b> CONTENT findings must
 *       cite ≥1 {@code evidenceId} resolving to an in-domain {@code Evidence}
 *       row — out-of-domain / nonexistent ids are dropped, and a CONTENT
 *       finding that loses all grounding is {@link Outcome#UNGROUNDED}.
 *       STRUCTURAL findings pass with or without evidence (self-justifying
 *       model-state observations).</li>
 *   <li><b>Assertion provenance.</b> Every KEPT candidate is persisted as an
 *       {@code Assertion(producedBy=AI_VALIDATIVE)} before being returned.</li>
 * </ul>
 *
 * <p>The per-candidate {@link Outcome} feeds the diagnostic batch ledger's
 * {@code filter_histogram} (spec §3.1: {badType, badSeverity, missingField,
 * ungrounded}). The §5.1 additive candidate fields ({@code title},
 * {@code findingType}) are passed through RAW and unvalidated — vocabulary
 * enforcement and defaulting belong to the commit step (§5.2), so an invalid
 * LLM-supplied findingType never discards a grounded candidate.
 */
@Service
public class CandidateFilter {

    private static final Logger log = LoggerFactory.getLogger(CandidateFilter.class);

    /**
     * Per-candidate filter outcome (spec §7.2). Exactly one outcome per raw
     * candidate; everything except {@link #KEPT} increments the batch
     * {@code filter_histogram}.
     */
    public enum Outcome {
        KEPT, BAD_TYPE, BAD_SEVERITY, MISSING_FIELD, UNGROUNDED
    }

    /**
     * Invocation context: the domain whose Evidence pool grounds CONTENT
     * candidates, plus the surface/scope recorded in Assertion provenance
     * ({@code producedByContext}).
     */
    public record Context(UUID domainId, Surface surface, String scope) {}

    /**
     * Aggregate result of filtering one raw-candidate batch.
     *
     * @param kept      surviving candidates, each already persisted as an
     *                  {@code Assertion(AI_VALIDATIVE)}, in input order
     * @param outcomes  one outcome per raw candidate, in input order
     * @param histogram non-KEPT outcome counts (the batch filter_histogram)
     */
    public record Result(List<FindingCandidate> kept,
                         List<Outcome> outcomes,
                         Map<Outcome, Integer> histogram) {

        /** Number of candidates that did not survive (≡ outcomes − kept). */
        public int filteredCount() {
            return outcomes.size() - kept.size();
        }
    }

    private final AssertionService assertionService;
    private final EvidenceRepository evidenceRepository;
    private final ObjectMapper mapper;

    public CandidateFilter(AssertionService assertionService,
                           EvidenceRepository evidenceRepository,
                           ObjectMapper mapper) {
        this.assertionService = assertionService;
        this.evidenceRepository = evidenceRepository;
        this.mapper = mapper;
    }

    /**
     * Filter a batch of raw candidate JSON objects against the context
     * domain's Evidence pool. Persists one {@code Assertion(AI_VALIDATIVE)}
     * per surviving candidate — the provenance row the commit step's
     * {@code Finding.assertion_id} will reference.
     */
    public Result filter(List<JsonNode> rawCandidates, Context context) {
        Set<UUID> domainEvidenceIds = evidenceRepository.findByDomainId(context.domainId())
                .stream()
                .map(Evidence::getId)
                .collect(Collectors.toSet());

        List<FindingCandidate> kept = new ArrayList<>();
        List<Outcome> outcomes = new ArrayList<>(rawCandidates.size());
        Map<Outcome, Integer> histogram = new EnumMap<>(Outcome.class);

        for (JsonNode raw : rawCandidates) {
            Evaluation evaluation = evaluate(raw, domainEvidenceIds, context);
            outcomes.add(evaluation.outcome);
            if (evaluation.outcome == Outcome.KEPT) {
                kept.add(evaluation.candidate);
            } else {
                histogram.merge(evaluation.outcome, 1, Integer::sum);
            }
        }
        return new Result(List.copyOf(kept), List.copyOf(outcomes), Map.copyOf(histogram));
    }

    private record Evaluation(Outcome outcome, FindingCandidate candidate) {
        static Evaluation filtered(Outcome outcome) {
            return new Evaluation(outcome, null);
        }
    }

    /**
     * Validate every required field, enforce both allowlists and the
     * differential grounding rule, then persist an Assertion and return the
     * KEPT candidate. Check order is the pinned phase2a order: type →
     * severity → required fields → grounding.
     */
    private Evaluation evaluate(JsonNode raw, Set<UUID> domainEvidenceIds, Context context) {
        FindingCandidateType type = parseType(raw);
        if (type == null) {
            log.debug("Filtered finding: invalid or missing 'type' (must be STRUCTURAL or CONTENT)");
            return Evaluation.filtered(Outcome.BAD_TYPE);
        }

        FindingCandidateSeverity severity = parseSeverity(raw);
        if (severity == null) {
            log.debug("Filtered finding: invalid or missing 'severity' (must be one of {})",
                    Arrays.toString(FindingCandidateSeverity.values()));
            return Evaluation.filtered(Outcome.BAD_SEVERITY);
        }

        String description = textOrNull(raw, "description");
        String suggestedAction = textOrNull(raw, "suggestedAction");
        if (description == null || description.isBlank()
                || suggestedAction == null || suggestedAction.isBlank()) {
            log.debug("Filtered finding: missing required field (description or suggestedAction)");
            return Evaluation.filtered(Outcome.MISSING_FIELD);
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
            return Evaluation.filtered(Outcome.UNGROUNDED);
        }

        Assertion assertion = persistAssertion(
                context, type, severity, affectedEntityType, affectedEntityId,
                description, suggestedAction, grounded);

        // §5.1 additive fields: raw passthrough, nullable, unvalidated here.
        return new Evaluation(Outcome.KEPT, new FindingCandidate(
                type, severity, affectedEntityType, affectedEntityId,
                description, suggestedAction, grounded, assertion.getId(),
                textOrNull(raw, "title"), textOrNull(raw, "findingType")));
    }

    private Assertion persistAssertion(Context context,
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
                : "domain:" + context.domainId();

        ObjectNode producedByContext = mapper.createObjectNode();
        producedByContext.put("mode", AiMode.VALIDATIVE.name());
        producedByContext.put("surface", context.surface().name());
        producedByContext.put("scope", context.scope());

        return assertionService.record(
                context.domainId(),
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
}
