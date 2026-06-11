package com.meridian.substrate.engine.diagnostic;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.DiagnosticRunBatch;
import com.meridian.substrate.common.domain.SynthesisRun;
import com.meridian.substrate.engine.ai.orchestration.OrchestratorResult;
import com.meridian.substrate.engine.ai.orchestration.Surface;
import com.meridian.substrate.engine.ai.persona.PersonaContext;
import com.meridian.substrate.engine.ai.validative.ValidateRequest;
import com.meridian.substrate.engine.ai.validative.ValidativeModeService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The DEFAULT generation executor (engine-spec diagnostic.md §8.1; ADR-004
 * D11) — degraded mode IS the base case: with
 * {@code substrate.orchestrator.enabled=false} this bean is the whole
 * generation story and exactly one bean ({@code OrchestratorDiagnosticExecutor},
 * build-order step 8) disappears. No other conditional paths.
 *
 * <p>Per batch: build a {@link ValidateRequest} and call
 * {@code ValidativeModeService.validateDetailed} → ToolCallingOrchestrator →
 * LlmClient ({@code SdkAnthropicClient}, or {@code MockAnthropicClient} under
 * {@code substrate.llm.provider=mock} — fixture seam S8, the CI lane).
 * Candidates come back already filtered + asserted by the mode service's
 * {@link CandidateFilter} chokepoint; this executor only carries them with the
 * {@link OrchestratorResult} token/model/refusal data for ledger/run
 * accounting.
 *
 * <p>This deliberately upgrades the orchestrator-substrate contract doc's
 * degraded-mode line ("operator-authored findings instead of AI generation")
 * to "direct-LlmClient generation still works" — the contract doc receives a
 * versioned amendment at slice close (ADR-004 follow-up #5).
 *
 * <p>The LLM invocation runs as the L3_ANALYST persona level: the analyst
 * levels hold the read-tool surface (incl. {@code find_evidence}, spec §7.1)
 * via the unchanged wildcard persona-access defaults; the human trigger actor
 * is recorded in run metadata, not impersonated at the tool boundary.
 */
@Service
public class InProcessDiagnosticExecutor implements DiagnosticExecutor {

    static final String ANALYST_PERSONA_LEVEL = "L3_ANALYST";

    private final ValidativeModeService validativeModeService;

    public InProcessDiagnosticExecutor(ValidativeModeService validativeModeService) {
        this.validativeModeService = validativeModeService;
    }

    @Override
    public String name() {
        return EXECUTOR_IN_PROCESS;
    }

    @Override
    public BatchGeneration executeBatch(SynthesisRun run, DiagnosticRunBatch batch) {
        ValidateRequest request = new ValidateRequest(
                run.getDomainId(),
                Surface.ANALYST_QUERY,
                batch.getScope(),
                targetIds(batch.getTargetEntityIds()));

        ValidativeModeService.DetailedValidation validation = validativeModeService
                .validateDetailed(request, PersonaContext.ofLevel(ANALYST_PERSONA_LEVEL));

        OrchestratorResult result = validation.orchestratorResult();
        return new BatchGeneration(
                validation.response().findings(),
                validation.rawCandidateCount(),
                validation.filterHistogram(),
                result.totalInputTokens(),
                result.totalOutputTokens(),
                result.model(),
                result.refusalDetected(),
                validation.response().explanation(),
                null);
    }

    private static List<UUID> targetIds(JsonNode targetEntityIds) {
        if (targetEntityIds == null || !targetEntityIds.isArray()) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>(targetEntityIds.size());
        for (JsonNode node : targetEntityIds) {
            try {
                ids.add(UUID.fromString(node.asText()));
            } catch (IllegalArgumentException notAUuid) {
                // planner-written JSON is always UUID strings; tolerate noise
            }
        }
        return ids;
    }
}
