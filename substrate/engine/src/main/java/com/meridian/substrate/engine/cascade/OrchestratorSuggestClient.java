package com.meridian.substrate.engine.cascade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.CascadeDraft;
import com.meridian.substrate.common.domain.Finding;
import com.meridian.substrate.common.domain.enums.CascadeSuggestionState;
import com.meridian.substrate.common.repo.FindingRepository;
import com.meridian.substrate.engine.contract.gateway.ContractDeniedException;
import com.meridian.substrate.engine.contract.gateway.ContractGateway;
import com.meridian.substrate.engine.contract.gateway.GovernedAction;
import com.meridian.substrate.engine.contract.gateway.GovernedActions;
import com.meridian.substrate.engine.contract.gateway.Principal;
import com.meridian.substrate.engine.contract.gateway.TargetRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * The engine's ONLY orchestrator touchpoint (engine-spec cascade.md §7;
 * ADR-001 D14): drives the AI-suggest flow for cascade drafts over the
 * {@link OrchestratorMcpClient} seam, used exclusively by
 * {@link CascadeDraftService#suggest}.
 *
 * <p>Bean exists only when {@code substrate.orchestrator.enabled=true} —
 * degraded mode is identical by construction (contract doc): with the flag
 * off, this bean and the {@code /suggest} endpoint are simply absent and
 * everything else runs untouched.
 *
 * <p>Flow (§7): {@code check_budget_for_task} preflight (refusal →
 * {@code suggestion_state=ERROR} with a provenance note, NO governance impact,
 * the human path continues) → {@code submit_task(agent_role="engineer",
 * task_type="cascade-critique-draft")} with a context pack that REFERENCES the
 * read-only §6.1 GET surface rather than dumping payloads → non-blocking
 * <b>on-read refresh</b> of {@code get_dispatch_status} (implementer's choice
 * per §7 step 3; no scheduled poll, no blocking HTTP in a request thread) →
 * on completion {@code get_dispatch_result} lands the {@code suggested_*}
 * columns and {@code get_dispatch_events} is digested into
 * {@code suggestion_provenance} JSON
 * {@code {taskId, model, eventsDigest, requestedAt, landedAt, producedBy}}.
 * {@code ProducedBy=AI_GENERATIVE} appears ONLY inside that provenance (ADR
 * D6); the issued cascade is HUMAN or AI_ASSISTIVE, decided at {@code /issue}.
 *
 * <p>All states written here ({@code PENDING/READY/ERROR}) are AI-assist
 * plumbing, never governance state; this class never throws to the API for
 * budget/dispatch outcomes.
 */
@Service
@ConditionalOnProperty(name = "substrate.orchestrator.enabled", havingValue = "true")
public class OrchestratorSuggestClient {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorSuggestClient.class);

    /** Contract-doc known task type for the cascade draft AI-suggest lane. */
    static final String TASK_TYPE = "cascade-critique-draft";
    /** Substrate submits at engineer altitude (contract doc: Engineer-or-lower). */
    static final String AGENT_ROLE = "engineer";

    private final OrchestratorMcpClient mcp;
    private final FindingRepository findingRepository;
    private final ContractGateway gateway;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OrchestratorSuggestClient(OrchestratorMcpClient mcp,
                                     FindingRepository findingRepository,
                                     ContractGateway gateway,
                                     ObjectMapper objectMapper,
                                     Clock clock) {
        this.mcp = mcp;
        this.findingRepository = findingRepository;
        this.gateway = gateway;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * §7 steps 1–2: budget preflight + dispatch. Mutates the (managed) draft
     * in the caller's transaction; the caller persists.
     */
    public void requestSuggestion(CascadeDraft draft) {
        Instant requestedAt = clock.instant();
        String contextPack = buildContextPack(draft);

        OrchestratorMcpClient.BudgetDecision budget;
        try {
            budget = mcp.checkBudgetForTask(TASK_TYPE, contextPack);
        } catch (RuntimeException transport) {
            markError(draft, "budget preflight failed: " + transport.getMessage(), requestedAt);
            log.warn("Cascade suggest budget preflight failed [draftId={}]",
                    draft.getId(), transport);
            return;
        }
        if (!budget.approved()) {
            // Budget refusal is a non-event for governance (contract doc).
            markError(draft, "budget refused: " + budget.note(), requestedAt);
            log.info("Cascade suggest budget refused [draftId={}, note={}]",
                    draft.getId(), budget.note());
            return;
        }
        try {
            String taskId = mcp.submitTask(AGENT_ROLE, contextPack, TASK_TYPE);
            draft.setOrchestratorTaskId(taskId);
            draft.setSuggestionState(CascadeSuggestionState.PENDING);
            ObjectNode provenance = objectMapper.createObjectNode();
            provenance.put("taskId", taskId);
            provenance.put("requestedAt", requestedAt.toString());
            draft.setSuggestionProvenance(provenance);
            log.info("Cascade suggest dispatched [draftId={}, taskId={}]", draft.getId(), taskId);
        } catch (RuntimeException transport) {
            markError(draft, "dispatch failed: " + transport.getMessage(), requestedAt);
            log.warn("Cascade suggest dispatch failed [draftId={}]", draft.getId(), transport);
        }
    }

    /**
     * §7 steps 3–4, on-read refresh: polls the dispatch once, landing the
     * suggestion when complete. No-op unless the draft is PENDING with a task
     * id. A transport hiccup leaves the draft PENDING (transient; the next
     * read retries) — only a terminal dispatch state moves the lane.
     *
     * @return whether the draft was mutated (caller persists when true)
     */
    public boolean refresh(CascadeDraft draft) {
        if (draft.getSuggestionState() != CascadeSuggestionState.PENDING
                || draft.getOrchestratorTaskId() == null) {
            return false;
        }
        String taskId = draft.getOrchestratorTaskId();
        OrchestratorMcpClient.DispatchState state;
        try {
            state = mcp.getDispatchStatus(taskId);
        } catch (RuntimeException transport) {
            log.warn("Cascade suggest status poll failed — staying pending [draftId={}, taskId={}]",
                    draft.getId(), taskId, transport);
            return false;
        }
        switch (state) {
            case IN_FLIGHT -> {
                return false;
            }
            case FAILED -> {
                markError(draft, "orchestrator dispatch did not complete (task " + taskId + ")",
                        provenanceInstant(draft, "requestedAt"));
                log.info("Cascade suggest dispatch ended without result [draftId={}, taskId={}]",
                        draft.getId(), taskId);
                return true;
            }
            case COMPLETED -> {
                OrchestratorMcpClient.DispatchResult result = mcp.getDispatchResult(taskId);
                List<String> events = mcp.getDispatchEvents(taskId);

                // §4.2 PEP 3 (ADR D2): applying an accepted get_dispatch_result
                // IS a governed action — actionType=apply_dispatch_result,
                // principal SERVICE/substrate-svc, carrying the dispatch id
                // (stamped on the decision row). The agent is an ADVISOR; its
                // output becomes substrate state only through this gate. An
                // enforce-mode DENY lands the draft in the ERROR plumbing lane
                // (never governance state, spec §2) with the denial cited —
                // reads stay functional and nothing is silently retried;
                // shadow rows WOULD_DENY and proceeds (ADR D4).
                try {
                    ObjectNode applySummary = objectMapper.createObjectNode();
                    applySummary.put("taskId", taskId);
                    if (result.model() != null) {
                        applySummary.put("model", result.model());
                    }
                    gateway.evaluate(new GovernedAction(GovernedActions.APPLY_DISPATCH_RESULT,
                            Principal.service(), draft.getDomainId(),
                            new TargetRef(CascadeGovernedResolvers.TARGET_CASCADE_DRAFT,
                                    draft.getId().toString()),
                            applySummary, taskId));
                } catch (ContractDeniedException denied) {
                    markError(draft, "apply_dispatch_result denied by the contract gateway: "
                            + denied.getMessage(), provenanceInstant(draft, "requestedAt"));
                    log.warn("Dispatch-result apply DENIED [draftId={}, taskId={}, decisionId={}]"
                                    + " — suggestion not landed (§4.2 PEP 3)",
                            draft.getId(), taskId, denied.getDecisionId());
                    return true;
                }

                draft.setSuggestedCritique(result.suggestedCritique());
                draft.setSuggestedAcceptanceCriteria(result.suggestedAcceptanceCriteria());
                ObjectNode provenance = objectMapper.createObjectNode();
                provenance.put("taskId", taskId);
                provenance.put("model", result.model());
                provenance.put("eventsDigest", sha256(events));
                Instant requestedAt = provenanceInstant(draft, "requestedAt");
                if (requestedAt != null) {
                    provenance.put("requestedAt", requestedAt.toString());
                }
                provenance.put("landedAt", clock.instant().toString());
                // AI_GENERATIVE lives ONLY inside this provenance (ADR D6).
                provenance.put("producedBy", "AI_GENERATIVE");
                draft.setSuggestionProvenance(provenance);
                draft.setSuggestionState(CascadeSuggestionState.READY);
                log.info("Cascade suggestion landed [draftId={}, taskId={}, model={}]",
                        draft.getId(), taskId, result.model());
                return true;
            }
        }
        return false;
    }

    // ----------------------------------------------------------------- helpers

    /**
     * §7 step 2 context pack: authoring intent + draft fields + linked Finding
     * text (when {@code origin_finding_id} resolves) + canonical org context as
     * read-only GET URL references — never payload dumps (contract doc: agents
     * read engagement context via the public §6.1 surface).
     */
    private String buildContextPack(CascadeDraft draft) {
        StringBuilder pack = new StringBuilder();
        pack.append("Authoring intent: draft a refined cascade critique and acceptance criteria")
                .append(" for HUMAN review and promotion (suggestions only — no write path).\n");
        pack.append("Authoring mode: ").append(draft.getAuthoringMode()).append('\n');
        pack.append("Domain id: ").append(draft.getDomainId()).append('\n');
        if (notBlank(draft.getOriginalCritique())) {
            pack.append("Draft critique: ").append(draft.getOriginalCritique()).append('\n');
        }
        if (notBlank(draft.getAcceptanceCriteria())) {
            pack.append("Draft acceptance criteria: ").append(draft.getAcceptanceCriteria()).append('\n');
        }
        if (notBlank(draft.getSummary())) {
            pack.append("Summary: ").append(draft.getSummary()).append('\n');
        }
        if (draft.getAffectedDomainId() != null) {
            pack.append("Affected domain: /api/v1/domains/").append(draft.getAffectedDomainId()).append('\n');
        }
        if (draft.getOriginFindingId() != null) {
            Finding finding = findingRepository.findById(draft.getOriginFindingId()).orElse(null);
            if (finding != null) {
                pack.append("Origin finding: ").append(finding.getTitle()).append(" — ")
                        .append(finding.getDescription()).append('\n');
            } else {
                pack.append("Origin finding id (unresolved soft ref): ")
                        .append(draft.getOriginFindingId()).append('\n');
            }
        }
        pack.append("Read-only context (GET, no auth in Phase 2):\n");
        pack.append("  /api/v1/cascades?domainId=").append(draft.getDomainId()).append('\n');
        pack.append("  /api/v1/cascades/stats?domainId=").append(draft.getDomainId()).append('\n');
        pack.append("  /api/v1/domains/").append(draft.getDomainId()).append('\n');
        return pack.toString();
    }

    /** ERROR is AI-assist plumbing only — never governance state (spec §2). */
    private void markError(CascadeDraft draft, String note, Instant requestedAt) {
        draft.setSuggestionState(CascadeSuggestionState.ERROR);
        ObjectNode provenance = objectMapper.createObjectNode();
        if (draft.getOrchestratorTaskId() != null) {
            provenance.put("taskId", draft.getOrchestratorTaskId());
        }
        provenance.put("note", note);
        if (requestedAt != null) {
            provenance.put("requestedAt", requestedAt.toString());
        }
        draft.setSuggestionProvenance(provenance);
    }

    private static Instant provenanceInstant(CascadeDraft draft, String field) {
        JsonNode provenance = draft.getSuggestionProvenance();
        if (provenance != null && provenance.hasNonNull(field)) {
            try {
                return Instant.parse(provenance.get(field).asText());
            } catch (RuntimeException ignored) {
                // best-effort carry-over only
            }
        }
        return null;
    }

    /** Events digest for the provenance audit trail: SHA-256 hex over the event lines. */
    static String sha256(List<String> events) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String event : events) {
                digest.update(event.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
