package com.meridian.substrate.common.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * AI-assist <em>plumbing</em> status of a draft's orchestrator suggestion
 * (engine-spec cascade.md §2, §6.3 DTO note; ADR D6/D14).
 *
 * <p>NEVER governance state: {@code ERROR} here means the orchestrator
 * dispatch failed (budget refusal, dispatch failure) — it says nothing about
 * declines or cascade outcomes. Decline-never-error applies to the governance
 * enums ({@link CascadeState}, {@link CascadeLevelState},
 * {@link CascadeActionType}, {@link CascadeAttentionSignal}), none of which
 * carry an error/failure value.
 *
 * <p>DB stores enum names; wire form is kebab-case via {@link JsonValue}
 * ({@code none}, {@code pending}, {@code ready}, {@code error}).
 */
public enum CascadeSuggestionState {
    NONE,
    PENDING,
    READY,
    ERROR;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
