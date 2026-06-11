package com.meridian.substrate.engine.cascade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.CascadeDraft;
import com.meridian.substrate.common.domain.enums.CascadeAuthoringMode;
import com.meridian.substrate.common.domain.enums.CascadeDraftStatus;
import com.meridian.substrate.common.repo.CascadeDraftRepository;
import com.meridian.substrate.common.repo.CascadeLevelActionRepository;
import com.meridian.substrate.common.repo.CascadeLevelRepository;
import com.meridian.substrate.common.repo.CascadeNotificationRepository;
import com.meridian.substrate.common.repo.CascadeRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Shared construction helpers for the cascade engine {@code @DataJpaTest}
 * slices. Services are built by hand (no component scan in the slice — see
 * {@link EngineCascadeJpaTestConfig}); the tests drive verbs through explicit
 * {@code TransactionTemplate} transactions, mirroring the decision-package
 * test pattern.
 */
final class CascadeEngineTestSupport {

    static final Instant T0 = Instant.parse("2026-04-08T00:00:00Z");
    static final String ORIGINATOR = "Operating Partner";
    static final String L1_OWNER = "Curasync COO";
    static final String L2_OWNER = "Curasync HIM Director";

    private CascadeEngineTestSupport() {
    }

    static Clock fixedClock() {
        return Clock.fixed(T0, ZoneOffset.UTC);
    }

    static CascadeProperties defaultProperties() {
        return new CascadeProperties();
    }

    static CascadeLifecycleService lifecycleService(CascadeRepository cascadeRepository,
                                                    CascadeLevelRepository levelRepository,
                                                    CascadeLevelActionRepository actionRepository,
                                                    CascadeDraftRepository draftRepository,
                                                    CascadeNotificationRepository notificationRepository,
                                                    CascadeAttestationHook hook,
                                                    CascadeProperties properties,
                                                    Clock clock) {
        return new CascadeLifecycleService(cascadeRepository, levelRepository, actionRepository,
                draftRepository,
                new CascadeNotificationService(notificationRepository, clock),
                hook, properties, clock, new ObjectMapper());
    }

    /** A fresh OPEN free-form draft, ready to issue. */
    static CascadeDraft openDraft(UUID domainId) {
        CascadeDraft draft = new CascadeDraft();
        draft.setDomainId(domainId);
        draft.setAuthorLabel(ORIGINATOR);
        draft.setOriginalCritique("Vendor cost overrun critique");
        draft.setAcceptanceCriteria("A remediation plan, or a Decline with documented reasoning");
        draft.setAuthoringMode(CascadeAuthoringMode.FREE_FORM);
        draft.setStatus(CascadeDraftStatus.OPEN);
        return draft;
    }

    static CascadeLifecycleService.IssueCommand issueCommand(String key) {
        return new CascadeLifecycleService.IssueCommand(
                key, CascadeActor.of(ORIGINATOR), L1_OWNER, null, null);
    }

    static CascadeLifecycleService.CascadeCommand cascadeCommand(String key, String actor,
                                                                 String nextOwner) {
        return new CascadeLifecycleService.CascadeCommand(
                key, null, CascadeActor.of(actor), "Refined by " + actor, null, nextOwner);
    }

    static CascadeLifecycleService.DeclineCommand declineCommand(String key, String actor) {
        return new CascadeLifecycleService.DeclineCommand(
                key, null, CascadeActor.of(actor), "Documented pushback by " + actor);
    }

    static CascadeLifecycleService.AbsorbCommand absorbCommand(String key, String actor) {
        return new CascadeLifecycleService.AbsorbCommand(
                key, null, CascadeActor.of(actor), "Absorbed by " + actor);
    }

    static CascadeLifecycleService.RecascadeCommand recascadeCommand(String key, String actor,
                                                                     String nextOwner) {
        return new CascadeLifecycleService.RecascadeCommand(
                key, null, CascadeActor.of(actor), "Revised refinement by " + actor, null, nextOwner);
    }

    static CascadeLifecycleService.ResolveCommand resolveLooksGood(String key) {
        return new CascadeLifecycleService.ResolveCommand(
                key, null, CascadeActor.of(ORIGINATOR),
                CascadeLifecycleService.Resolution.LOOKS_GOOD, "Outcome accepted", null);
    }

    static CascadeLifecycleService.ResolveCommand resolveDeclined(String key, UUID acceptedDeclineId) {
        return new CascadeLifecycleService.ResolveCommand(
                key, null, CascadeActor.of(ORIGINATOR),
                CascadeLifecycleService.Resolution.DECLINED, "Decline accepted", acceptedDeclineId);
    }
}
