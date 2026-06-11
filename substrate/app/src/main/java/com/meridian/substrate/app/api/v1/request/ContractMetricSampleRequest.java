package com.meridian.substrate.app.api.v1.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * {@code POST /contracts/{idOrKey}/metric-samples} body (engine-spec
 * contracts.md §7.2/§5.5; ADR D11 push-based v1 feed): idempotent upsert on
 * {@code (contract, party, metricKey, periodKey)}. Caller identity is
 * recorded verbatim (advisory pre-Phase-5, spec §15 #12). Feeds the
 * METRIC_THRESHOLD evaluator and §2.6 decline-trigger arming.
 */
public record ContractMetricSampleRequest(
        @NotNull UUID partyId,
        @NotBlank String metricKey,
        @NotNull Double value,
        @NotBlank String periodKey
) {}
