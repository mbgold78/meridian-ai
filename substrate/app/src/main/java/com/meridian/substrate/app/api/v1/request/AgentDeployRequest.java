package com.meridian.substrate.app.api.v1.request;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * {@code POST /agents} body (engine-spec agents.md §5.4/§7.2). Validation
 * annotations live HERE only, per the port rule — but DELIBERATELY only for
 * request-shape concerns (idempotency key, slug/name presence, ranges,
 * cadence vocabulary): the §5.4 deploy-gate fields
 * ({@code deployedByLabel}, {@code deploymentCriteria}, {@code oversightChain},
 * {@code supervisoryModel}, {@code executionLocus}, readiness pairing) carry
 * NO bean-validation annotations so the service gate answers with their
 * STRUCTURED per-field codes (e.g. {@code AGENT_PROVENANCE_MISSING_OVERSIGHT}),
 * not a generic {@code VALIDATION_ERROR}.
 *
 * <p>Enum-shaped fields arrive as kebab-case wire strings (spec §3) and are
 * parsed at the controller — unknown values 400 via the
 * {@code IllegalArgumentException} mapping; absent values flow {@code null}
 * into the service gate for the structured missing-field codes.
 */
public record AgentDeployRequest(
        @NotBlank @Size(max = 128) String idempotencyKey,
        @NotNull UUID domainId,
        @NotBlank @Size(max = 160) String agentKey,
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 512) String roleDescription,
        String executionLocus,                  // wire: substrate-mcp | orchestrator | customer-system
        UUID capabilityId,
        @Size(max = 255) String deployedByLabel,
        UUID deployedByMemberId,
        String deploymentCriteria,
        @NotEmpty @Valid List<CriteriaGateRequest> criteriaGates,
        @Valid List<OversightEntryRequest> oversightChain,
        String supervisoryModel,                // wire: full-auto | human-review-above-threshold | human-in-loop
        UUID targetProcessId,
        JsonNode targetStepIds,
        @Min(0) @Max(100) Integer agentReadinessScore,
        String readinessSource,                 // wire: readiness-lens | operator-asserted
        JsonNode requiredSystemAccess,
        JsonNode requiredDataAccess,
        UUID initiativeId,
        String notes,
        Boolean readOnly,
        JsonNode scheduleWindow,
        @Min(0) @Max(100) Integer samplingPct,
        @Size(max = 255) String mcpPrincipalId,
        /* M2: deploy-to-LIVE requires a non-blank justification; @Pattern skips null. */
        @Pattern(regexp = "(?s).*\\S.*",
                message = "liveJustification must be non-blank when present (M2)")
        String liveJustification,
        UUID predecessorRegistrationId
) {

    /** One ordered mandate clause (§7.2 {@code criteriaGates![…]} / §9.1). */
    public record CriteriaGateRequest(
            @NotBlank String gateText,
            @NotBlank String ruleType,          // wire: structured | unstructured
            JsonNode predicate,
            @Pattern(regexp = "(?i)MONTHLY|QUARTERLY|WEEKLY|DAILY",
                    message = "cadence must be MONTHLY, QUARTERLY, WEEKLY or DAILY")
            String cadence
    ) {}

    /** One ordered oversight-chain entry (§7.2 {@code oversightChain![…]}). */
    public record OversightEntryRequest(
            String label,                       // service gate: AGENT_PROVENANCE_MISSING_OVERSIGHT when blank
            UUID memberId
    ) {}
}
