package com.meridian.substrate.engine.cascade.read;

import com.meridian.substrate.common.domain.CascadeLevel;
import com.meridian.substrate.common.domain.enums.CascadeLevelState;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * One level row of {@link CascadeDetail#levels()} — 1:1 with the Beacon
 * {@code CascadeLevel} view type (engine-spec cascade.md §6.1/§11.1; ADR D13):
 * {@code level, ownerLabel, state, refinedCritique?, declineReasoning?,
 * timestamp} plus the additive engine fields. Kebab-case {@code state} via the
 * enum's {@code @JsonValue}.
 *
 * <p>{@code timestamp} is the acted/activated ISO <em>date</em> (fixture
 * compat, §11.1): the acted date when the level has acted, else the activation
 * date — frontier LOOKING rows the fixtures never contained ship with their
 * activation date (§11.2).
 */
public record CascadeLevelDto(
        int level,
        String ownerLabel,
        UUID ownerMemberId,
        CascadeLevelState state,
        int round,
        String refinedCritique,
        String declineReasoning,
        Instant activatedAt,
        Instant actedAt,
        String timestamp,
        Instant responseDeadline,
        boolean overdue
) {

    public static CascadeLevelDto from(CascadeLevel level) {
        return new CascadeLevelDto(
                level.getDepth(),
                level.getOwnerLabel(),
                level.getOwnerMemberId(),
                level.getCurrentState(),
                level.getRound(),
                level.getRefinedCritique(),
                level.getDeclineReasoning(),
                level.getActivatedAt(),
                level.getActedAt(),
                isoDate(level.getActedAt() != null ? level.getActedAt() : level.getActivatedAt()),
                level.getResponseDeadline(),
                level.isOverdue());
    }

    private static String isoDate(Instant instant) {
        return instant == null ? null
                : DateTimeFormatter.ISO_LOCAL_DATE.format(instant.atOffset(ZoneOffset.UTC));
    }
}
