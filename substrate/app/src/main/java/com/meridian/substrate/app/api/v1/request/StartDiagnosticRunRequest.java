package com.meridian.substrate.app.api.v1.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/v1/diagnostics/runs} body (engine-spec diagnostic.md
 * §10.1): {@code domainId} and {@code idempotencyKey} are required; the
 * optionals fall back to {@code substrate.diagnostic.*} defaults.
 * {@code dryRun=true} returns the deterministic batch plan (200) without
 * creating anything.
 */
public record StartDiagnosticRunRequest(
        @NotNull(message = "domainId is required")
        UUID domainId,
        List<String> scopes,
        List<UUID> targetEntityIds,
        @Min(value = 1, message = "batchSize must be >= 1")
        Integer batchSize,
        @Min(value = 0, message = "requiredMinFindings must be >= 0")
        Integer requiredMinFindings,
        String executor,
        Boolean dryRun,
        @NotBlank(message = "idempotencyKey is required")
        String idempotencyKey
) {}
