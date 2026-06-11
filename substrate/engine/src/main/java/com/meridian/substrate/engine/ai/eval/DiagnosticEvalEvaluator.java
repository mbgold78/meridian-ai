package com.meridian.substrate.engine.ai.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.ai.LlmClient;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.domain.Evidence;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.common.repo.EvidenceRepository;
import com.meridian.substrate.engine.ai.client.MockAnthropicClient;
import com.meridian.substrate.engine.ai.generative.GenerativeModeService;
import com.meridian.substrate.engine.ai.orchestration.Surface;
import com.meridian.substrate.engine.ai.persona.PersonaContext;
import com.meridian.substrate.engine.ai.validative.FindingCandidate;
import com.meridian.substrate.engine.ai.validative.ValidateRequest;
import com.meridian.substrate.engine.ai.validative.ValidativeModeService;
import com.meridian.substrate.engine.diagnostic.CandidateFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Diagnostic-path eval support (engine-spec diagnostic.md §13; severs S17 for
 * the diagnostic path): evaluates {@code mode: validative | generative} eval
 * cases for {@link PromptRegressionHarness}, asserting structured
 * parseability + filter outcomes.
 *
 * <p><b>One chokepoint, no re-derivation:</b>
 * <ul>
 *   <li><b>validative</b> outcomes come from the REAL {@link CandidateFilter}
 *       (payload cases) or the full pinned {@link ValidativeModeService}
 *       pipeline (no-payload cases — the tool-grounding lane, where
 *       {@code find_evidence} is dispatched for real against
 *       {@code MockAnthropicClient}'s scripted responder);</li>
 *   <li><b>generative</b> outcomes mirror {@code GenerativeModeService}'s
 *       documented order (grounding → allowlist) with the allowlist read from
 *       {@link GenerativeModeService#allowedFieldsForEntityType} — never a
 *       private copy; {@code PromptRegressionHarnessTest} pins
 *       mirror-vs-service parity by driving the real service over the same
 *       payload.</li>
 * </ul>
 *
 * <p><b>Deterministic grounding:</b> a case may declare {@code seedEvidence: N}
 * — the evaluator creates a throwaway eval domain plus N Evidence rows
 * (everything labeled {@code eval-harness}) and substitutes
 * <code>{{evidence:i}}</code> placeholders in the case payload with the real
 * ids, so grounded-survives cases are deterministic under the mock provider.
 * The EvalRunner's documented command runs against a throwaway dev database;
 * the seeded rows are inert, clearly-labeled noise anywhere else.
 *
 * <p>Outcome comparison is multiset (alphabetically normalized): the
 * no-payload lane reconstructs outcomes from kept-count + filter histogram,
 * which carries no candidate order.
 */
@Service
@ConditionalOnProperty(name = "substrate.eval.enabled", havingValue = "true", matchIfMissing = false)
public class DiagnosticEvalEvaluator {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticEvalEvaluator.class);

    static final String OUTCOME_NO_ALLOWLISTED_FIELDS = "NO_ALLOWLISTED_FIELDS";
    static final String OUTCOME_UNGROUNDED = "UNGROUNDED";
    static final String OUTCOME_KEPT = "KEPT";

    static final String ANALYST_PERSONA_LEVEL = "L3_ANALYST";
    static final String SEED_LABEL = "eval-harness";

    private final CandidateFilter candidateFilter;
    private final ValidativeModeService validativeModeService;
    private final GenerativeModeService generativeModeService;
    private final EvidenceRepository evidenceRepository;
    private final DomainRepository domainRepository;
    private final LlmClient llmClient;
    private final ObjectMapper mapper;

    public DiagnosticEvalEvaluator(CandidateFilter candidateFilter,
                                   ValidativeModeService validativeModeService,
                                   GenerativeModeService generativeModeService,
                                   EvidenceRepository evidenceRepository,
                                   DomainRepository domainRepository,
                                   LlmClient llmClient,
                                   ObjectMapper mapper) {
        this.candidateFilter = candidateFilter;
        this.validativeModeService = validativeModeService;
        this.generativeModeService = generativeModeService;
        this.evidenceRepository = evidenceRepository;
        this.domainRepository = domainRepository;
        this.llmClient = llmClient;
        this.mapper = mapper;
    }

    /** What a diagnostic-mode case needs evaluated (a slice of the TestCase shape). */
    public record CaseSpec(String mode,
                           String input,
                           String candidatePayload,
                           String mockScript,
                           int seedEvidence,
                           String scope,
                           String entityType) {
    }

    /** Evaluation result: normalized (sorted) outcomes + the pattern-assertable summary line. */
    public record Evaluation(List<String> outcomes, String summary) {
    }

    public Evaluation evaluate(CaseSpec spec) {
        Seed seed = seedEvidence(spec.seedEvidence());
        String scope = spec.scope() == null || spec.scope().isBlank() ? "systems" : spec.scope();

        if ("generative".equalsIgnoreCase(spec.mode())) {
            return evaluateGenerative(spec, seed.domainId(), seed.evidenceIds());
        }
        return evaluateValidative(spec, seed.domainId(), seed.evidenceIds(), scope);
    }

    // ------------------------------------------------------------- validative

    private Evaluation evaluateValidative(CaseSpec spec, UUID domainId, List<UUID> seeded,
                                          String scope) {
        if (spec.candidatePayload() != null && !spec.candidatePayload().isBlank()) {
            // Deterministic JSON-contract lane: the payload stands in for the
            // model's final text; outcomes come from the REAL chokepoint.
            List<JsonNode> raw = parseArray(substitute(spec.candidatePayload(), seeded));
            if (raw.isEmpty()) {
                return new Evaluation(List.of(), "parsed=0 outcomes=[] (no parseable candidate array)");
            }
            CandidateFilter.Result result = candidateFilter.filter(raw,
                    new CandidateFilter.Context(domainId, Surface.ANALYST_QUERY, scope));
            List<String> outcomes = normalize(result.outcomes().stream()
                    .map(Enum::name)
                    .collect(Collectors.toList()));
            return new Evaluation(outcomes, "parsed=" + raw.size()
                    + " outcomes=" + outcomes
                    + " kept=" + keptSummary(result.kept()));
        }

        // Full-pipeline lane (tool grounding): the pinned mode service drives
        // the orchestrator loop; find_evidence is dispatched for real.
        String previousScript = armMockScript(spec.mockScript());
        try {
            ValidativeModeService.DetailedValidation validation =
                    validativeModeService.validateDetailed(
                            new ValidateRequest(domainId, Surface.ANALYST_QUERY, scope, List.of()),
                            PersonaContext.ofLevel(ANALYST_PERSONA_LEVEL));
            List<String> outcomes = new ArrayList<>();
            validation.response().findings().forEach(f -> outcomes.add(OUTCOME_KEPT));
            validation.filterHistogram().forEach((outcome, count) -> {
                for (int i = 0; i < count; i++) {
                    outcomes.add(outcome.name());
                }
            });
            List<String> normalized = normalize(outcomes);
            String toolCalls = validation.orchestratorResult().toolCalls().stream()
                    .map(trace -> trace.toolName())
                    .collect(Collectors.joining(","));
            return new Evaluation(normalized, "parsed=" + validation.rawCandidateCount()
                    + " outcomes=" + normalized
                    + " kept=" + keptSummary(validation.response().findings())
                    + " toolCalls=[" + toolCalls + "]");
        } finally {
            disarmMockScript(previousScript);
        }
    }

    private String keptSummary(List<FindingCandidate> kept) {
        return kept.stream()
                .map(candidate -> "{type=" + candidate.type()
                        + ",severity=" + candidate.severity()
                        + ",title=" + candidate.title()
                        + ",findingType=" + candidate.findingType()
                        + ",evidence=" + (candidate.evidenceIds() == null
                                ? 0 : candidate.evidenceIds().size()) + "}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    // ------------------------------------------------------------- generative

    /**
     * Mirror of the pinned generative filter ORDER (grounding → allowlist),
     * allowlist read from the service itself; parity with
     * {@code GenerativeModeService.generate} is pinned by test (§13).
     */
    private Evaluation evaluateGenerative(CaseSpec spec, UUID domainId, List<UUID> seeded) {
        String entityType = spec.entityType() == null || spec.entityType().isBlank()
                ? "capability" : spec.entityType();

        if (spec.candidatePayload() == null || spec.candidatePayload().isBlank()) {
            // Full-pipeline lane: counts only (the pinned response shape has no
            // per-candidate outcome vocabulary).
            var response = generativeModeService.generate(
                    new com.meridian.substrate.engine.ai.generative.GenerateRequest(
                            domainId, Surface.ANALYST_QUERY, entityType, List.of()),
                    PersonaContext.ofLevel(ANALYST_PERSONA_LEVEL));
            List<String> outcomes = new ArrayList<>();
            response.drafts().forEach(d -> outcomes.add(OUTCOME_KEPT));
            List<String> normalized = normalize(outcomes);
            return new Evaluation(normalized, "drafts=" + response.drafts().size()
                    + " filtered=" + response.filteredCount()
                    + " outcomes=" + normalized);
        }

        Set<String> allowedFields = GenerativeModeService.allowedFieldsForEntityType(entityType);
        if (allowedFields == null) {
            return new Evaluation(List.of(),
                    "outcomes=[] (no allowlist for entityType '" + entityType + "' — the service rejects it)");
        }
        Set<UUID> domainEvidence = evidenceRepository.findByDomainId(domainId).stream()
                .map(Evidence::getId)
                .collect(Collectors.toSet());

        List<JsonNode> raw = parseArray(substitute(spec.candidatePayload(), seeded));
        if (raw.isEmpty()) {
            return new Evaluation(List.of(), "parsed=0 outcomes=[] (no parseable proposal array)");
        }

        List<String> outcomes = new ArrayList<>();
        List<String> keptSummaries = new ArrayList<>();
        for (JsonNode candidate : raw) {
            boolean grounded = extractUuids(candidate.get("evidenceIds")).stream()
                    .anyMatch(domainEvidence::contains);
            if (!grounded) {
                outcomes.add(OUTCOME_UNGROUNDED);
                continue;
            }
            Set<String> proposed = new TreeSet<>();
            Set<String> dropped = new TreeSet<>();
            Iterator<String> names = candidate.fieldNames();
            while (names.hasNext()) {
                String field = names.next();
                if ("evidenceIds".equals(field) || "confidence".equals(field)) {
                    continue;
                }
                JsonNode value = candidate.get(field);
                if (allowedFields.contains(field) && value != null && !value.isNull()) {
                    proposed.add(field);
                } else {
                    dropped.add(field);
                }
            }
            if (proposed.isEmpty()) {
                outcomes.add(OUTCOME_NO_ALLOWLISTED_FIELDS);
                continue;
            }
            outcomes.add(OUTCOME_KEPT);
            keptSummaries.add("{proposedFields=" + proposed + ",dropped=" + dropped + "}");
        }
        List<String> normalized = normalize(outcomes);
        return new Evaluation(normalized, "parsed=" + raw.size()
                + " outcomes=" + normalized
                + " kept=" + keptSummaries);
    }

    // ---------------------------------------------------------------- seeding

    /** The case's grounding domain: throwaway row when evidence was seeded, random id otherwise. */
    private record Seed(UUID domainId, List<UUID> evidenceIds) {
    }

    /** Throwaway eval domain + N labeled Evidence rows (ids in seed order). */
    private Seed seedEvidence(int count) {
        if (count <= 0) {
            return new Seed(UUID.randomUUID(), List.of());
        }
        Domain domain = new Domain();
        domain.setName(SEED_LABEL + "-" + UUID.randomUUID());
        domain.setCreatedBy(SEED_LABEL);
        domain = domainRepository.save(domain);

        List<UUID> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Evidence evidence = new Evidence();
            evidence.setDomainId(domain.getId());
            evidence.setEntityId(UUID.randomUUID());
            evidence.setEntityType("system");
            evidence.setLabel(SEED_LABEL + " evidence " + i);
            evidence.setSourceFile(SEED_LABEL + ".csv");
            evidence.setExtractor(SEED_LABEL);
            evidence.setConfidence(1.0f);
            evidence.setCreatedBy(SEED_LABEL);
            ids.add(evidenceRepository.save(evidence).getId());
        }
        return new Seed(domain.getId(), List.copyOf(ids));
    }

    // ---------------------------------------------------------------- helpers

    private String substitute(String payload, List<UUID> seeded) {
        String out = payload;
        for (int i = 0; i < seeded.size(); i++) {
            out = out.replace("{{evidence:" + i + "}}", seeded.get(i).toString());
        }
        return out;
    }

    /** Tolerant array extraction — the same posture as the mode services. */
    private List<JsonNode> parseArray(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();
        }
        try {
            JsonNode parsed = mapper.readTree(text.substring(start, end + 1));
            if (!parsed.isArray()) {
                return List.of();
            }
            List<JsonNode> out = new ArrayList<>();
            parsed.forEach(out::add);
            return out;
        } catch (JsonProcessingException e) {
            log.warn("Eval payload did not parse as JSON: {}", e.getMessage());
            return List.of();
        }
    }

    private List<UUID> extractUuids(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<UUID> out = new ArrayList<>();
        for (JsonNode node : array) {
            if (!node.isTextual()) {
                continue;
            }
            try {
                out.add(UUID.fromString(node.asText()));
            } catch (IllegalArgumentException ignored) {
                // non-UUID strings are skipped, mirroring the services
            }
        }
        return out;
    }

    /** Multiset normalization: alphabetical — the no-payload lane has no candidate order. */
    private static List<String> normalize(List<String> outcomes) {
        return outcomes.stream().sorted().toList();
    }

    private String armMockScript(String script) {
        if (script == null || script.isBlank()) {
            return null;
        }
        if (llmClient instanceof MockAnthropicClient mock) {
            mock.setDiagnosticScript(script);
            return "";
        }
        log.info("mockScript '{}' requested but the live LlmClient is not the mock provider — "
                + "running the case against the real model", script);
        return null;
    }

    private void disarmMockScript(String previous) {
        if (previous != null && llmClient instanceof MockAnthropicClient mock) {
            mock.setDiagnosticScript(previous);
        }
    }
}
