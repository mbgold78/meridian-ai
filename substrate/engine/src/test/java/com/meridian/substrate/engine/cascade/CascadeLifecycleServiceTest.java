package com.meridian.substrate.engine.cascade;

import com.meridian.substrate.common.domain.Cascade;
import com.meridian.substrate.common.domain.CascadeDraft;
import com.meridian.substrate.common.domain.CascadeLevel;
import com.meridian.substrate.common.domain.CascadeLevelAction;
import com.meridian.substrate.common.domain.enums.CascadeActionType;
import com.meridian.substrate.common.domain.enums.CascadeAttentionSignal;
import com.meridian.substrate.common.domain.enums.CascadeDraftStatus;
import com.meridian.substrate.common.domain.enums.CascadeLevelState;
import com.meridian.substrate.common.domain.enums.CascadeNotificationType;
import com.meridian.substrate.common.domain.enums.CascadeState;
import com.meridian.substrate.common.domain.enums.ProducedBy;
import com.meridian.substrate.common.repo.CascadeDraftRepository;
import com.meridian.substrate.common.repo.CascadeLevelActionRepository;
import com.meridian.substrate.common.repo.CascadeLevelRepository;
import com.meridian.substrate.common.repo.CascadeNotificationRepository;
import com.meridian.substrate.common.repo.CascadeRepository;
import com.meridian.substrate.engine.cascade.exception.CascadeConflictException;
import com.meridian.substrate.engine.cascade.exception.CascadeForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.L1_OWNER;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.L2_OWNER;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.ORIGINATOR;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.T0;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.absorbCommand;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.cascadeCommand;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.declineCommand;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.issueCommand;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.openDraft;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.recascadeCommand;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.resolveDeclined;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.resolveLooksGood;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service-level coverage on the H2 fast lane: D8 mechanics — mandatory
 * idempotency-key replay (test plan #4, service half), {@code expectedChainSeq}
 * fencing, draft-anchored issuance idempotency — plus actor guards, terminal
 * absorption, SLA resolution at issue, overdue clearing, sibling advisories
 * (ADR D2) and the attestation hook seam (D15).
 *
 * <p>Verbs run inside explicit {@link TransactionTemplate} transactions
 * (PESSIMISTIC_WRITE needs an active transaction; the services are
 * hand-constructed so {@code @Transactional} proxies are not in play).
 * Testcontainers-Postgres remains the acceptance gate for lock/trigger
 * semantics (H2-green is never acceptance; this machine cannot run Docker).
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cascade-lifecycle;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CascadeLifecycleServiceTest {

    @Autowired
    private CascadeRepository cascadeRepository;
    @Autowired
    private CascadeLevelRepository levelRepository;
    @Autowired
    private CascadeLevelActionRepository actionRepository;
    @Autowired
    private CascadeDraftRepository draftRepository;
    @Autowired
    private CascadeNotificationRepository notificationRepository;
    @Autowired
    private PlatformTransactionManager txManager;

    private CascadeLifecycleService service;
    private RecordingHook hook;
    private TransactionTemplate tx;
    private int keySeq;

    static final class RecordingHook implements CascadeAttestationHook {
        final List<CascadeLevelAction> resolved = new ArrayList<>();

        @Override
        public void onResolved(Cascade cascade, CascadeLevelAction resolveAction) {
            resolved.add(resolveAction);
        }
    }

    @BeforeEach
    void setUp() {
        hook = new RecordingHook();
        service = CascadeEngineTestSupport.lifecycleService(cascadeRepository, levelRepository,
                actionRepository, draftRepository, notificationRepository, hook,
                CascadeEngineTestSupport.defaultProperties(), CascadeEngineTestSupport.fixedClock());
        tx = new TransactionTemplate(txManager);
        keySeq = 0;
    }

    private <T> T inTx(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    private String key() {
        return "key-" + UUID.randomUUID() + "-" + (++keySeq);
    }

    private UUID persistedDraftId() {
        return inTx(() -> draftRepository.save(openDraft(UUID.randomUUID()))).getId();
    }

    /** issue → L1 cascade → L2 decline: the c-vendor-cost shape (spec §11.4). */
    private Cascade issuedCascadedDeclined() {
        UUID draftId = persistedDraftId();
        Cascade cascade = inTx(() -> service.issue(draftId, issueCommand(key())));
        inTx(() -> service.cascade(cascade.getId(), 1, cascadeCommand(key(), L1_OWNER, L2_OWNER)));
        inTx(() -> service.decline(cascade.getId(), 2, declineCommand(key(), L2_OWNER)));
        return cascadeRepository.findById(cascade.getId()).orElseThrow();
    }

    private List<CascadeLevelAction> actionsOf(UUID cascadeId) {
        return actionRepository.findByCascadeIdOrderByChainSeqAsc(cascadeId);
    }

    // ------------------------------------------------------------------- issue

    @Test
    void issue_createsCascadeLevelsActionNotificationsAndFlipsDraft_inOneCommit() {
        UUID draftId = persistedDraftId();
        Cascade cascade = inTx(() -> service.issue(draftId, issueCommand(key())));

        assertThat(cascade.getCurrentState()).isEqualTo(CascadeState.IN_FLIGHT);
        assertThat(cascade.getChainSeq()).isEqualTo(1);
        assertThat(cascade.getFrontierDepth()).isEqualTo(1);
        assertThat(cascade.getFrontierState()).isEqualTo(CascadeLevelState.LOOKING);
        assertThat(cascade.getAttentionSignal()).isEqualTo(CascadeAttentionSignal.ESCALATING);
        assertThat(cascade.getOriginOwnerLabel()).isEqualTo(ORIGINATOR);
        assertThat(cascade.getOriginatedAt()).isEqualTo(T0);
        assertThat(cascade.getProducedBy()).isEqualTo(ProducedBy.HUMAN);
        // SLA resolution at issue: absent on the draft → engagement default, stored resolved.
        assertThat(cascade.getResponseSlaDays()).isEqualTo(7);

        List<CascadeLevel> levels = levelRepository.findByCascadeIdOrderByDepthAsc(cascade.getId());
        assertThat(levels).hasSize(2);
        assertThat(levels.get(0).getCurrentState()).isEqualTo(CascadeLevelState.CASCADED);
        assertThat(levels.get(0).getOwnerLabel()).isEqualTo(ORIGINATOR);
        // originatorRefinedCritique defaulted to the original critique (§5.2 A1).
        assertThat(levels.get(0).getRefinedCritique()).isEqualTo("Vendor cost overrun critique");
        assertThat(levels.get(1).getCurrentState()).isEqualTo(CascadeLevelState.LOOKING);
        assertThat(levels.get(1).getOwnerLabel()).isEqualTo(L1_OWNER);
        assertThat(levels.get(1).getResponseDeadline()).isEqualTo(T0.plus(Duration.ofDays(7)));

        List<CascadeLevelAction> actions = actionsOf(cascade.getId());
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).getAction()).isEqualTo(CascadeActionType.ISSUE);
        assertThat(actions.get(0).getDepth()).isZero();
        assertThat(actions.get(0).getChainSeq()).isEqualTo(1);
        assertThat(actions.get(0).getNextOwnerLabel()).isEqualTo(L1_OWNER);
        assertThat(actions.get(0).getLevelId()).isEqualTo(levels.get(0).getId());

        CascadeDraft draft = draftRepository.findById(draftId).orElseThrow();
        assertThat(draft.getStatus()).isEqualTo(CascadeDraftStatus.ISSUED);
        assertThat(draft.getIssuedCascadeId()).isEqualTo(cascade.getId());

        assertThat(notificationRepository.findByCascadeIdOrderByCreatedAtAsc(cascade.getId()))
                .extracting(n -> n.getNotificationType())
                .containsExactlyInAnyOrder(CascadeNotificationType.CASCADE_ISSUED,
                        CascadeNotificationType.LEVEL_ACTIVATED);
    }

    @Test
    void issue_explicitSlaZero_disablesTheDeadline() {
        UUID draftId = inTx(() -> {
            CascadeDraft d = openDraft(UUID.randomUUID());
            d.setResponseSlaDays(0);
            return draftRepository.save(d);
        }).getId();

        Cascade cascade = inTx(() -> service.issue(draftId, issueCommand(key())));
        assertThat(cascade.getResponseSlaDays()).isZero();
        assertThat(levelRepository.findByCascadeIdAndDepth(cascade.getId(), 1).orElseThrow()
                .getResponseDeadline()).isNull();
    }

    @Test
    void reissue_ofIssuedDraft_returnsTheExistingCascade_idempotent() {
        UUID draftId = persistedDraftId();
        Cascade first = inTx(() -> service.issue(draftId, issueCommand(key())));
        Cascade second = inTx(() -> service.issue(draftId, issueCommand(key())));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(actionsOf(first.getId())).hasSize(1);
    }

    @Test
    void issue_ofDiscardedDraft_conflicts() {
        UUID draftId = inTx(() -> {
            CascadeDraft d = openDraft(UUID.randomUUID());
            d.setStatus(CascadeDraftStatus.DISCARDED);
            return draftRepository.save(d);
        }).getId();

        assertThatThrownBy(() -> inTx(() -> service.issue(draftId, issueCommand(key()))))
                .isInstanceOf(CascadeConflictException.class)
                .satisfies(t -> assertThat(((CascadeConflictException) t).getCode())
                        .isEqualTo(CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION));
    }

    @Test
    void issue_requiresMandatoryIdempotencyKey() {
        UUID draftId = persistedDraftId();
        assertThatThrownBy(() -> inTx(() -> service.issue(draftId, issueCommand(" "))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey");
    }

    // -------------------------------------------------- idempotency + fencing (D8)

    @Test
    void replaySameIdempotencyKey_returnsWithoutAppendingASecondAction() {
        UUID draftId = persistedDraftId();
        Cascade cascade = inTx(() -> service.issue(draftId, issueCommand(key())));
        UUID id = cascade.getId();
        inTx(() -> service.cascade(id, 1, cascadeCommand(key(), L1_OWNER, L2_OWNER)));

        String declineKey = key();
        inTx(() -> service.decline(id, 2, declineCommand(declineKey, L2_OWNER)));
        assertThat(actionsOf(id)).hasSize(3);

        Cascade replayed = inTx(() -> service.decline(id, 2, declineCommand(declineKey, L2_OWNER)));

        assertThat(actionsOf(id)).hasSize(3); // no new row (D8 replay)
        assertThat(replayed.getChainSeq()).isEqualTo(3);
        assertThat(replayed.getAttentionSignal()).isEqualTo(CascadeAttentionSignal.DECLINE_PENDING);
    }

    @Test
    void replay_winsOverEveryOtherCheck_evenAfterTheChainMovedOn() {
        Cascade cascade = issuedCascadedDeclined();
        UUID id = cascade.getId();
        UUID declineActionId = actionsOf(id).get(2).getId();
        String resolveKey = key();
        inTx(() -> service.resolve(id, resolveDeclined(resolveKey, declineActionId)));

        // Replaying the resolve on the now-terminal cascade is a 200 replay,
        // never CASCADE_TERMINAL.
        Cascade replayed = inTx(() -> service.resolve(id, resolveDeclined(resolveKey, declineActionId)));
        assertThat(replayed.getCurrentState()).isEqualTo(CascadeState.RESOLVED_DECLINED);
        assertThat(actionsOf(id)).hasSize(4);
    }

    @Test
    void staleExpectedChainSeq_throwsStaleState_withCascadeIdForTheEnvelope() {
        Cascade cascade = issuedCascadedDeclined(); // chain_seq is now 3
        UUID id = cascade.getId();
        CascadeLifecycleService.AbsorbCommand stale = new CascadeLifecycleService.AbsorbCommand(
                key(), 1, CascadeActor.of(L1_OWNER), "Absorbing over the decline");

        assertThatThrownBy(() -> inTx(() -> service.absorb(id, 1, stale)))
                .isInstanceOf(CascadeConflictException.class)
                .satisfies(t -> {
                    CascadeConflictException e = (CascadeConflictException) t;
                    assertThat(e.getCode()).isEqualTo(CascadeConflictException.Code.CASCADE_STALE_STATE);
                    assertThat(e.getCascadeId()).isEqualTo(id);
                });
        assertThat(actionsOf(id)).hasSize(3);
    }

    @Test
    void matchingExpectedChainSeq_passesTheFence() {
        Cascade cascade = issuedCascadedDeclined();
        UUID id = cascade.getId();
        CascadeLifecycleService.AbsorbCommand current = new CascadeLifecycleService.AbsorbCommand(
                key(), 3, CascadeActor.of(L1_OWNER), "Absorbing over the decline");

        Cascade after = inTx(() -> service.absorb(id, 1, current));
        assertThat(after.getChainSeq()).isEqualTo(4);
        assertThat(after.getAttentionSignal()).isEqualTo(CascadeAttentionSignal.ABSORBED);
    }

    // ------------------------------------------------------------ guards + terminal

    @Test
    void wrongActor_onALevelVerb_isForbidden_andAppendsNothing() {
        Cascade cascade = issuedCascadedDeclined();
        UUID id = cascade.getId();

        assertThatThrownBy(() -> inTx(() ->
                service.recascade(id, 1, recascadeCommand(key(), "Impostor", L2_OWNER))))
                .isInstanceOf(CascadeForbiddenException.class)
                .satisfies(t -> assertThat(((CascadeForbiddenException) t).getCode())
                        .isEqualTo(CascadeForbiddenException.Code.CASCADE_NOT_LEVEL_OWNER));
        assertThat(actionsOf(id)).hasSize(3);
    }

    @Test
    void nonOriginator_resolve_isForbidden() {
        Cascade cascade = issuedCascadedDeclined();
        UUID id = cascade.getId();
        UUID declineActionId = actionsOf(id).get(2).getId();
        CascadeLifecycleService.ResolveCommand byL2 = new CascadeLifecycleService.ResolveCommand(
                key(), null, CascadeActor.of(L2_OWNER),
                CascadeLifecycleService.Resolution.DECLINED, "note", declineActionId);

        assertThatThrownBy(() -> inTx(() -> service.resolve(id, byL2)))
                .isInstanceOf(CascadeForbiddenException.class)
                .satisfies(t -> assertThat(((CascadeForbiddenException) t).getCode())
                        .isEqualTo(CascadeForbiddenException.Code.CASCADE_NOT_ORIGINATOR));
    }

    @Test
    void terminalCascade_rejectsAnyFurtherAction() {
        Cascade cascade = issuedCascadedDeclined();
        UUID id = cascade.getId();
        UUID declineActionId = actionsOf(id).get(2).getId();
        inTx(() -> service.resolve(id, resolveDeclined(key(), declineActionId)));

        assertThatThrownBy(() -> inTx(() ->
                service.recascade(id, 1, recascadeCommand(key(), L1_OWNER, L2_OWNER))))
                .isInstanceOf(CascadeConflictException.class)
                .satisfies(t -> assertThat(((CascadeConflictException) t).getCode())
                        .isEqualTo(CascadeConflictException.Code.CASCADE_TERMINAL));
    }

    // -------------------------------------------------- decline-path continuations

    @Test
    void resolveDeclined_recordsTheResolutionAccountabilityBlock_andInvokesTheHook() {
        Cascade cascade = issuedCascadedDeclined();
        UUID id = cascade.getId();
        CascadeLevelAction decline = actionsOf(id).get(2);
        assertThat(decline.getAction()).isEqualTo(CascadeActionType.DECLINE);

        Cascade resolved = inTx(() -> service.resolve(id, resolveDeclined(key(), decline.getId())));

        assertThat(resolved.getCurrentState()).isEqualTo(CascadeState.RESOLVED_DECLINED);
        assertThat(resolved.getAttentionSignal()).isEqualTo(CascadeAttentionSignal.RESOLVED_DECLINED);
        assertThat(resolved.getResolvedAt()).isEqualTo(T0);
        assertThat(resolved.getResolvedByLabel()).isEqualTo(ORIGINATOR);
        assertThat(resolved.getResolutionNote()).isEqualTo("Decline accepted");
        assertThat(resolved.getAcceptedDeclineActionId()).isEqualTo(decline.getId());
        assertThat(hook.resolved).hasSize(1);
        assertThat(hook.resolved.get(0).getAction()).isEqualTo(CascadeActionType.RESOLVE_DECLINED);
    }

    @Test
    void endorseDecline_thenOriginatorAcceptsTheUpperDecline() {
        Cascade cascade = issuedCascadedDeclined();
        UUID id = cascade.getId();
        inTx(() -> service.decline(id, 1, declineCommand(key(), L1_OWNER))); // endorse

        CascadeLevelAction endorse = actionsOf(id).get(3);
        assertThat(endorse.getDepth()).isEqualTo(1);

        Cascade resolved = inTx(() -> service.resolve(id, resolveDeclined(key(), endorse.getId())));
        assertThat(resolved.getCurrentState()).isEqualTo(CascadeState.RESOLVED_DECLINED);
        assertThat(resolved.getAcceptedDeclineActionId()).isEqualTo(endorse.getId());
    }

    @Test
    void recascade_activatesRoundTwo_andTheChainCanDeepen() {
        Cascade cascade = issuedCascadedDeclined();
        UUID id = cascade.getId();
        inTx(() -> service.recascade(id, 1, recascadeCommand(key(), L1_OWNER, L2_OWNER)));

        CascadeLevel l2 = levelRepository.findByCascadeIdAndDepth(id, 2).orElseThrow();
        assertThat(l2.getCurrentState()).isEqualTo(CascadeLevelState.LOOKING);
        assertThat(l2.getRound()).isEqualTo(2);

        inTx(() -> service.cascade(id, 2, cascadeCommand(key(), L2_OWNER, "Curasync Analyst")));
        CascadeLevel l3 = levelRepository.findByCascadeIdAndDepth(id, 3).orElseThrow();
        assertThat(l3.getCurrentState()).isEqualTo(CascadeLevelState.LOOKING);
        assertThat(l3.getRound()).isEqualTo(1);
        assertThat(cascadeRepository.findById(id).orElseThrow().getFrontierDepth()).isEqualTo(3);
    }

    @Test
    void resolveLooksGood_anyTimeInFlight_overALookingFrontier() {
        UUID draftId = persistedDraftId();
        Cascade cascade = inTx(() -> service.issue(draftId, issueCommand(key())));
        UUID id = cascade.getId();

        Cascade resolved = inTx(() -> service.resolve(id, resolveLooksGood(key())));
        assertThat(resolved.getCurrentState()).isEqualTo(CascadeState.RESOLVED_LOOKS_GOOD);
        assertThat(hook.resolved).hasSize(1);
        // The LOOKING level remains as the honest record of the short-circuit.
        assertThat(levelRepository.findByCascadeIdAndDepth(id, 1).orElseThrow().getCurrentState())
                .isEqualTo(CascadeLevelState.LOOKING);
    }

    @Test
    void resolveDeclined_acceptingAStaleRoundDecline_conflicts() {
        Cascade cascade = issuedCascadedDeclined();
        UUID id = cascade.getId();
        UUID round1Decline = actionsOf(id).get(2).getId();
        inTx(() -> service.recascade(id, 1, recascadeCommand(key(), L1_OWNER, L2_OWNER)));
        inTx(() -> service.decline(id, 2, declineCommand(key(), L2_OWNER))); // round-2 decline

        assertThatThrownBy(() -> inTx(() -> service.resolve(id, resolveDeclined(key(), round1Decline))))
                .isInstanceOf(CascadeConflictException.class)
                .satisfies(t -> assertThat(((CascadeConflictException) t).getCode())
                        .isEqualTo(CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION));
    }

    // --------------------------------------------------- overdue + sibling advisories

    @Test
    void overdueFlags_areClearedOnAnyTransitionOfTheAffectedLevel() {
        UUID draftId = persistedDraftId();
        Cascade cascade = inTx(() -> service.issue(draftId, issueCommand(key())));
        UUID id = cascade.getId();
        inTx(() -> {
            // Simulate a sweeper pass: level + cascade flagged overdue.
            CascadeLevel l1 = levelRepository.findByCascadeIdAndDepth(id, 1).orElseThrow();
            l1.setOverdue(true);
            levelRepository.save(l1);
            Cascade c = cascadeRepository.findById(id).orElseThrow();
            c.setOverdue(true);
            return cascadeRepository.save(c);
        });

        Cascade after = inTx(() -> service.decline(id, 1, declineCommand(key(), L1_OWNER)));

        assertThat(after.isOverdue()).isFalse();
        assertThat(levelRepository.findByCascadeIdAndDepth(id, 1).orElseThrow().isOverdue()).isFalse();
    }

    @Test
    void siblingDecline_advisoryRowOncePerDeclingCascadeSiblingPair_zeroStateChange() {
        UUID groupId = UUID.randomUUID();
        UUID domainId = UUID.randomUUID();
        UUID drafted1 = inTx(() -> {
            CascadeDraft d = openDraft(domainId);
            d.setCascadeGroupId(groupId);
            return draftRepository.save(d);
        }).getId();
        UUID drafted2 = inTx(() -> {
            CascadeDraft d = openDraft(domainId);
            d.setCascadeGroupId(groupId);
            return draftRepository.save(d);
        }).getId();
        Cascade declining = inTx(() -> service.issue(drafted1, issueCommand(key())));
        Cascade sibling = inTx(() -> service.issue(drafted2, issueCommand(key())));
        UUID decliningId = declining.getId();
        inTx(() -> service.cascade(decliningId, 1, cascadeCommand(key(), L1_OWNER, L2_OWNER)));

        inTx(() -> service.decline(decliningId, 2, declineCommand(key(), L2_OWNER)));
        inTx(() -> service.decline(decliningId, 1, declineCommand(key(), L1_OWNER))); // endorse

        // Exactly ONE advisory per declining-cascade/sibling pair, anchored to
        // the sibling, regardless of how many levels decline (§8, ADR D2).
        var advisories = notificationRepository.findByCascadeIdAndNotificationType(
                sibling.getId(), CascadeNotificationType.SIBLING_DECLINE);
        assertThat(advisories).hasSize(1);
        assertThat(advisories.get(0).getDedupeKey())
                .isEqualTo("sibling:" + sibling.getId() + ":" + decliningId);
        assertThat(advisories.get(0).getRecipientLabel()).isEqualTo(ORIGINATOR);
        assertThat(advisories.get(0).getPayload().get("decliningCascadeId").asText())
                .isEqualTo(decliningId.toString());

        // Zero state coupling on the sibling.
        Cascade siblingAfter = cascadeRepository.findById(sibling.getId()).orElseThrow();
        assertThat(siblingAfter.getCurrentState()).isEqualTo(CascadeState.IN_FLIGHT);
        assertThat(siblingAfter.getChainSeq()).isEqualTo(1);
        assertThat(siblingAfter.getAttentionSignal()).isEqualTo(CascadeAttentionSignal.ESCALATING);
    }

    @Test
    void declineFlows_neverThrowForALegitimateDecline_firstTouchAndEndorse() {
        // The service-level half of the decline-never-error contract (the
        // HTTP-envelope half is pinned in the app module's command-API chunk):
        // legitimate declines return normally — no exception, no failure path.
        Cascade cascade = issuedCascadedDeclined();
        UUID id = cascade.getId();
        Cascade afterEndorse = inTx(() -> service.decline(id, 1, declineCommand(key(), L1_OWNER)));
        assertThat(afterEndorse.getAttentionSignal()).isEqualTo(CascadeAttentionSignal.DECLINE_PENDING);
        assertThat(afterEndorse.getCurrentState()).isEqualTo(CascadeState.IN_FLIGHT);
    }
}
