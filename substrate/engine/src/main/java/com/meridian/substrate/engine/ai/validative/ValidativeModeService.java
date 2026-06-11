package com.meridian.substrate.engine.ai.validative;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.enums.FindingType;
import com.meridian.substrate.engine.ai.orchestration.AiMode;
import com.meridian.substrate.engine.ai.orchestration.OrchestratorResult;
import com.meridian.substrate.engine.ai.orchestration.SurfaceContext;
import com.meridian.substrate.engine.ai.orchestration.ToolCallingOrchestrator;
import com.meridian.substrate.engine.ai.persona.PersonaContext;
import com.meridian.substrate.engine.diagnostic.CandidateFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
 *       {@code Assertion} with {@code ProducedBy.AI_VALIDATIVE} so a future
 *       audit can trace what the AI claimed at validation time.</li>
 * </ul>
 *
 * <p><b>Diagnostic-engine refactor (engine-spec diagnostic.md §7.2, build step
 * 3):</b> the allowlist + differential-grounding + Assertion core above now
 * lives in {@link CandidateFilter} (the single grounding chokepoint shared
 * with the orchestrator-dispatched executor lane); this service delegates and
 * its public contract is byte-identical — the pinned phase2a suites pass
 * unmodified. The only candidate-contract change is §5.1-additive: candidates
 * MAY carry optional {@code title} / {@code findingType} fields (raw
 * passthrough; commit-step concern), and the prompt names the
 * {@code find_evidence} tool (§7.1) so the model can discover citable
 * evidence ids against freshly-ingested data.
 */
@Service
public class ValidativeModeService {

    private static final Logger log = LoggerFactory.getLogger(ValidativeModeService.class);

    private final ToolCallingOrchestrator orchestrator;
    private final CandidateFilter candidateFilter;
    private final ObjectMapper mapper;

    public ValidativeModeService(ToolCallingOrchestrator orchestrator,
                                 CandidateFilter candidateFilter,
                                 ObjectMapper mapper) {
        this.orchestrator = orchestrator;
        this.candidateFilter = candidateFilter;
        this.mapper = mapper;
    }

    public ValidateResponse validate(ValidateRequest request, PersonaContext persona) {
        return validateDetailed(request, persona).response();
    }

    /**
     * The §8.1 ledger-accounting variant (engine-spec diagnostic.md §8.1,
     * build step 4): same pipeline as {@link #validate} — which now delegates
     * here, behavior byte-identical — additionally exposing what the batch
     * ledger needs and the pinned {@link ValidateResponse} cannot carry: the
     * raw parseable-candidate count, the per-reason
     * {@link CandidateFilter.Outcome} histogram ({@code filter_histogram},
     * spec §3.1), and the {@link OrchestratorResult} token/model/refusal data
     * (run accounting, spec §4.5). ADDITIVE method only — the pinned phase2a
     * public contract is untouched and its suites pass unmodified.
     */
    public DetailedValidation validateDetailed(ValidateRequest request, PersonaContext persona) {
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
            return new DetailedValidation(
                    new ValidateResponse(List.of(), 0,
                            "AI did not return any structured findings."),
                    0, Map.of(), result);
        }

        CandidateFilter.Result filterResult = candidateFilter.filter(
                rawFindings,
                new CandidateFilter.Context(request.domainId(), request.surface(), request.scope()));

        List<FindingCandidate> kept = filterResult.kept();
        int filtered = filterResult.filteredCount();

        log.info("Validative produced [findings={}, filtered={}]", kept.size(), filtered);

        String explanation = kept.isEmpty()
                ? String.format(
                        "AI returned %d finding(s); all were filtered (type/severity allowlist, missing required fields, or unmet grounding requirement).",
                        rawFindings.size())
                : null;

        return new DetailedValidation(
                new ValidateResponse(kept, filtered, explanation),
                rawFindings.size(), filterResult.histogram(), result);
    }

    /**
     * {@link #validateDetailed} result: the pinned {@link ValidateResponse}
     * plus the diagnostic batch ledger's accounting data (spec §3.1/§4.5).
     * Zero parseable candidates ⇒ {@code rawCandidateCount=0} with an empty
     * histogram — the §10.2 NO_CANDIDATES lane stays structurally
     * distinguishable from all-filtered.
     */
    public record DetailedValidation(
            ValidateResponse response,
            int rawCandidateCount,
            Map<CandidateFilter.Outcome, Integer> filterHistogram,
            OrchestratorResult orchestratorResult
    ) {}

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
        sb.append("Optional: title (short, max 200 characters); findingType: one of ")
                .append(Arrays.toString(FindingType.values())).append("; ");
        sb.append("affectedEntityType, affectedEntityId (UUID).\n");
        sb.append("evidenceIds: array of UUID strings — REQUIRED for CONTENT findings, optional for STRUCTURAL. ");
        sb.append("Use the find_evidence tool to discover citable evidence ids in this domain; ");
        sb.append("CONTENT findings citing evidence ids you did not retrieve will be filtered.\n\n");
        sb.append("Findings will be filtered if: type or severity is outside the allowed set; ");
        sb.append("description or suggestedAction is missing; or a CONTENT finding lacks grounding evidence in this domain. ");
        sb.append("Do not include prose around the JSON array.");
        return sb.toString();
    }
}
