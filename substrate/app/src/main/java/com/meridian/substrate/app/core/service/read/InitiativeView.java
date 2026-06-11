package com.meridian.substrate.app.core.service.read;

import com.meridian.substrate.common.domain.TransformationInitiative;
import com.meridian.substrate.common.domain.enums.InitiativeComplexity;
import com.meridian.substrate.common.domain.enums.InitiativeStatus;
import com.meridian.substrate.common.domain.enums.InitiativeType;

import java.time.Instant;
import java.util.UUID;

/**
 * Initiative row of the D5 subsidiary composition (engine-spec diagnostic.md
 * §12.5/§12.7 — V8 thin slice). {@code progressPct} is the RENDERED value:
 * {@code status=COMPLETE ⇒ 100} is the only computed rule anywhere in the
 * slice; everything else is operator-maintained ({@code progressUpdatedAt}
 * surfaces staleness — a reviewer sees HOW OLD the operator's number is,
 * the DTO never hides it). No derivation from cascade/phase activity exists
 * (rejected as fabricated analytics, ADR-004 D13).
 */
public record InitiativeView(
        UUID id,
        UUID planId,
        String name,
        String description,
        InitiativeType initiativeType,
        InitiativeStatus status,
        Integer wave,
        String ownerName,
        Integer estimatedMonths,
        InitiativeComplexity complexity,
        Long annualSavingsUsd,
        int progressPct,
        String progressNote,
        Instant progressUpdatedAt
) {

    /** §12.5 exact rule: {@code renderedProgressPct(i) := i.status = COMPLETE ? 100 : i.progress_pct}. */
    public static int renderedProgressPct(TransformationInitiative initiative) {
        return initiative.getStatus() == InitiativeStatus.COMPLETE ? 100 : initiative.getProgressPct();
    }

    public static InitiativeView from(TransformationInitiative i) {
        return new InitiativeView(i.getId(), i.getPlanId(), i.getName(), i.getDescription(),
                i.getInitiativeType(), i.getStatus(), i.getWave(), i.getOwnerName(),
                i.getEstimatedMonths(), i.getComplexity(), i.getAnnualSavingsUsd(),
                renderedProgressPct(i), i.getProgressNote(), i.getProgressUpdatedAt());
    }
}
