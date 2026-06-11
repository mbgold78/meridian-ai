package com.meridian.substrate.engine.cascade;

import com.meridian.substrate.common.domain.Cascade;
import com.meridian.substrate.common.domain.CascadeLevel;
import com.meridian.substrate.common.domain.enums.CascadeAttentionSignal;
import com.meridian.substrate.common.domain.enums.CascadeLevelState;
import com.meridian.substrate.common.domain.enums.CascadeState;
import com.meridian.substrate.common.repo.CascadeDraftRepository;
import com.meridian.substrate.common.repo.CascadeLevelActionRepository;
import com.meridian.substrate.common.repo.CascadeLevelRepository;
import com.meridian.substrate.common.repo.CascadeNotificationRepository;
import com.meridian.substrate.common.repo.CascadeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.L1_OWNER;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.L2_OWNER;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.cascadeCommand;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.declineCommand;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.issueCommand;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.openDraft;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.recascadeCommand;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.resolveDeclined;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test plan #7 (engine-spec cascade.md §12): rebuilder determinism — seed via
 * the real lifecycle service, snapshot the projections, corrupt/wipe
 * {@code cascade_levels} and the derived {@code cascades} fields, rebuild,
 * assert field-equality with the snapshot; plus continuous drift detection
 * (live projections == fresh rebuild after every continuation vector), the
 * §10 overdue/deadline recomputation from clock + SLA config, and the
 * immutable-issuance verification (I3: assert, never rewrite).
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cascade-rebuilder;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CascadeProjectionRebuilderTest {

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
    private CascadeProjectionRebuilder rebuilder;
    private TransactionTemplate tx;
    private int keySeq;

    @BeforeEach
    void setUp() {
        Clock clock = CascadeEngineTestSupport.fixedClock();
        CascadeProperties properties = CascadeEngineTestSupport.defaultProperties();
        service = CascadeEngineTestSupport.lifecycleService(cascadeRepository, levelRepository,
                actionRepository, draftRepository, notificationRepository,
                (cascade, action) -> { /* no-op hook */ }, properties, clock);
        rebuilder = new CascadeProjectionRebuilder(cascadeRepository, levelRepository,
                actionRepository, properties, clock);
        tx = new TransactionTemplate(txManager);
        keySeq = 0;
    }

    private <T> T inTx(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    private String key() {
        return "key-" + UUID.randomUUID() + "-" + (++keySeq);
    }

    private UUID issueFreshCascade() {
        UUID draftId = inTx(() -> draftRepository.save(openDraft(UUID.randomUUID()))).getId();
        return inTx(() -> service.issue(draftId, issueCommand(key()))).getId();
    }

    // ----------------------------------------------------------- snapshot helpers

    private record LevelSnap(int depth, UUID ownerMemberId, String ownerLabel,
                             CascadeLevelState state, int round, String refinedCritique,
                             String declineReasoning, Instant activatedAt, Instant actedAt,
                             Instant responseDeadline, boolean overdue) {
        static LevelSnap of(CascadeLevel l) {
            return new LevelSnap(l.getDepth(), l.getOwnerMemberId(), l.getOwnerLabel(),
                    l.getCurrentState(), l.getRound(), l.getRefinedCritique(),
                    l.getDeclineReasoning(), l.getActivatedAt(), l.getActedAt(),
                    l.getResponseDeadline(), l.isOverdue());
        }
    }

    private record CascadeSnap(CascadeState state, int chainSeq, int frontierDepth,
                               CascadeLevelState frontierState, CascadeAttentionSignal signal,
                               boolean overdue, Instant resolvedAt, UUID resolvedByMemberId,
                               String resolvedByLabel, String resolutionNote,
                               UUID acceptedDeclineActionId) {
        static CascadeSnap of(Cascade c) {
            return new CascadeSnap(c.getCurrentState(), c.getChainSeq(), c.getFrontierDepth(),
                    c.getFrontierState(), c.getAttentionSignal(), c.isOverdue(), c.getResolvedAt(),
                    c.getResolvedByMemberId(), c.getResolvedByLabel(), c.getResolutionNote(),
                    c.getAcceptedDeclineActionId());
        }
    }

    private CascadeSnap cascadeSnap(UUID id) {
        return CascadeSnap.of(cascadeRepository.findById(id).orElseThrow());
    }

    private List<LevelSnap> levelSnaps(UUID id) {
        return levelRepository.findByCascadeIdOrderByDepthAsc(id).stream()
                .map(LevelSnap::of)
                .collect(Collectors.toList());
    }

    private void assertRebuildIsDriftFree(UUID id) {
        CascadeSnap cascadeBefore = cascadeSnap(id);
        List<LevelSnap> levelsBefore = levelSnaps(id);
        inTx(() -> rebuilder.rebuild(id));
        assertThat(cascadeSnap(id)).isEqualTo(cascadeBefore);
        assertThat(levelSnaps(id)).isEqualTo(levelsBefore);
    }

    // ------------------------------------------------------------------- tests

    @Test
    void rebuild_afterEveryContinuationVector_matchesTheLiveProjections() {
        UUID id = issueFreshCascade();
        assertRebuildIsDriftFree(id); // after ISSUE

        inTx(() -> service.cascade(id, 1, cascadeCommand(key(), L1_OWNER, L2_OWNER)));
        assertRebuildIsDriftFree(id); // after CASCADE

        inTx(() -> service.decline(id, 2, declineCommand(key(), L2_OWNER)));
        assertRebuildIsDriftFree(id); // after DECLINE (decline-pending)

        inTx(() -> service.recascade(id, 1, recascadeCommand(key(), L1_OWNER, L2_OWNER)));
        assertRebuildIsDriftFree(id); // after RECASCADE (round 2)

        inTx(() -> service.cascade(id, 2, cascadeCommand(key(), L2_OWNER, "Curasync Analyst")));
        assertRebuildIsDriftFree(id); // deepened after recascade

        inTx(() -> service.decline(id, 3, declineCommand(key(), "Curasync Analyst")));
        UUID declineId = actionRepository.findByCascadeIdOrderByChainSeqAsc(id).get(5).getId();
        inTx(() -> service.resolve(id, resolveDeclined(key(), declineId)));
        assertRebuildIsDriftFree(id); // terminal: resolution block re-derived too
    }

    @Test
    void rebuild_restoresCorruptedLevelsAndWipedDerivedFields_toTheSnapshot() {
        UUID id = issueFreshCascade();
        inTx(() -> service.cascade(id, 1, cascadeCommand(key(), L1_OWNER, L2_OWNER)));
        inTx(() -> service.decline(id, 2, declineCommand(key(), L2_OWNER)));

        CascadeSnap cascadeBefore = cascadeSnap(id);
        List<LevelSnap> levelsBefore = levelSnaps(id);

        // Corrupt: flip level projections, wipe derived aggregate fields,
        // delete one level row entirely, plant a bogus extra level.
        inTx(() -> {
            CascadeLevel l2 = levelRepository.findByCascadeIdAndDepth(id, 2).orElseThrow();
            l2.setCurrentState(CascadeLevelState.CASCADED);
            l2.setOwnerLabel("Corrupted Owner");
            l2.setDeclineReasoning(null);
            l2.setRound(9);
            l2.setOverdue(true);
            levelRepository.save(l2);

            levelRepository.delete(levelRepository.findByCascadeIdAndDepth(id, 1).orElseThrow());

            CascadeLevel bogus = new CascadeLevel();
            bogus.setCascadeId(id);
            bogus.setDepth(7);
            bogus.setOwnerLabel("Ghost");
            bogus.setCurrentState(CascadeLevelState.LOOKING);
            bogus.setActivatedAt(Instant.EPOCH);
            levelRepository.save(bogus);

            Cascade c = cascadeRepository.findById(id).orElseThrow();
            c.setCurrentState(CascadeState.IN_FLIGHT);
            c.setChainSeq(99);
            c.setFrontierDepth(7);
            c.setFrontierState(CascadeLevelState.LOOKING);
            c.setAttentionSignal(CascadeAttentionSignal.ABSORBED);
            c.setOverdue(true);
            return cascadeRepository.save(c);
        });

        inTx(() -> rebuilder.rebuild(id));

        assertThat(cascadeSnap(id)).isEqualTo(cascadeBefore);
        assertThat(levelSnaps(id)).isEqualTo(levelsBefore);
        // The bogus depth-7 row (not derivable from the action log) is gone.
        assertThat(levelRepository.findByCascadeIdAndDepth(id, 7)).isEmpty();
        // Surviving rows kept their identity (action.level_id soft refs stay valid).
        assertThat(levelRepository.findByCascadeIdAndDepth(id, 2)).isPresent();
    }

    @Test
    void rebuild_isIdempotent_backToBackRunsProduceIdenticalProjections() {
        UUID id = issueFreshCascade();
        inTx(() -> service.cascade(id, 1, cascadeCommand(key(), L1_OWNER, L2_OWNER)));
        inTx(() -> service.decline(id, 2, declineCommand(key(), L2_OWNER)));

        inTx(() -> rebuilder.rebuild(id));
        CascadeSnap first = cascadeSnap(id);
        List<LevelSnap> firstLevels = levelSnaps(id);

        inTx(() -> rebuilder.rebuild(id));
        assertThat(cascadeSnap(id)).isEqualTo(first);
        assertThat(levelSnaps(id)).isEqualTo(firstLevels);
    }

    @Test
    void rebuild_recomputesOverdueOverlay_fromSlaConfigAndClock() {
        UUID id = issueFreshCascade(); // L1 LOOKING, deadline T0+7d

        // A rebuilder whose clock sits past the deadline must flag the demand.
        CascadeProjectionRebuilder lateRebuilder = new CascadeProjectionRebuilder(
                cascadeRepository, levelRepository, actionRepository,
                CascadeEngineTestSupport.defaultProperties(),
                Clock.fixed(CascadeEngineTestSupport.T0.plus(Duration.ofDays(8)), ZoneOffset.UTC));
        inTx(() -> lateRebuilder.rebuild(id));

        assertThat(levelRepository.findByCascadeIdAndDepth(id, 1).orElseThrow().isOverdue()).isTrue();
        assertThat(cascadeRepository.findById(id).orElseThrow().isOverdue()).isTrue();

        // And the on-time rebuilder clears it again — deterministic per clock.
        inTx(() -> rebuilder.rebuild(id));
        assertThat(levelRepository.findByCascadeIdAndDepth(id, 1).orElseThrow().isOverdue()).isFalse();
        assertThat(cascadeRepository.findById(id).orElseThrow().isOverdue()).isFalse();
    }

    @Test
    void rebuild_verifiesImmutableIssuanceArtifacts_andRefusesOnDrift() {
        UUID id = issueFreshCascade();
        inTx(() -> {
            Cascade c = cascadeRepository.findById(id).orElseThrow();
            c.setOriginOwnerLabel("Tampered Originator");
            return cascadeRepository.save(c);
        });

        assertThatThrownBy(() -> inTx(() -> rebuilder.rebuild(id)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("I3");
    }

    @Test
    void rebuildAll_walksEveryCascadeOfTheDomain() {
        UUID domainId = UUID.randomUUID();
        UUID draft1 = inTx(() -> draftRepository.save(openDraft(domainId))).getId();
        UUID draft2 = inTx(() -> draftRepository.save(openDraft(domainId))).getId();
        UUID id1 = inTx(() -> service.issue(draft1, issueCommand(key()))).getId();
        UUID id2 = inTx(() -> service.issue(draft2, issueCommand(key()))).getId();

        List<UUID> rebuilt = inTx(() -> rebuilder.rebuildAll(domainId));
        assertThat(rebuilt).containsExactlyInAnyOrder(id1, id2);
    }

    @Test
    void rebuild_withoutAnyActions_refusesLoudly() {
        // A cascades row with no ISSUE action cannot be rebuilt — assert, never invent.
        UUID id = inTx(() -> {
            Cascade c = new Cascade();
            c.setDomainId(UUID.randomUUID());
            c.setOriginOwnerLabel("X");
            c.setOriginatedAt(CascadeEngineTestSupport.T0);
            c.setOriginalCritique("x");
            c.setAcceptanceCriteria("x");
            c.setAuthoringMode(com.meridian.substrate.common.domain.enums.CascadeAuthoringMode.FREE_FORM);
            c.setProducedBy(com.meridian.substrate.common.domain.enums.ProducedBy.HUMAN);
            c.setCurrentState(CascadeState.IN_FLIGHT);
            c.setFrontierDepth(1);
            c.setFrontierState(CascadeLevelState.LOOKING);
            c.setAttentionSignal(CascadeAttentionSignal.ESCALATING);
            return cascadeRepository.save(c);
        }).getId();

        assertThatThrownBy(() -> inTx(() -> rebuilder.rebuild(id)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not rebuildable");
    }
}
