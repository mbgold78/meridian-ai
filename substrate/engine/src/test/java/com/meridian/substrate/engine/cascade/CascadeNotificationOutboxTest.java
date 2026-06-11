package com.meridian.substrate.engine.cascade;

import com.meridian.substrate.common.domain.Cascade;
import com.meridian.substrate.common.domain.CascadeNotification;
import com.meridian.substrate.common.domain.enums.CascadeNotificationType;
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
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.L1_OWNER;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.L2_OWNER;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.ORIGINATOR;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.absorbCommand;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.cascadeCommand;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.declineCommand;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.issueCommand;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.openDraft;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.recascadeCommand;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.resolveLooksGood;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test plan #10 (engine-spec cascade.md §12): the notification outbox is
 * written IN the action transaction (ADR D10) — visibility inside the open
 * transaction, atomicity under rollback — plus the §8 fan-out table per
 * action and the crash-safety architecture assertion: no
 * {@code @TransactionalEventListener} exists anywhere in {@code engine.cascade}
 * (an AFTER_COMMIT write could silently drop rows on a crash between commit
 * and listener).
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cascade-outbox;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CascadeNotificationOutboxTest {

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
    private TransactionTemplate tx;
    private int keySeq;

    @BeforeEach
    void setUp() {
        service = CascadeEngineTestSupport.lifecycleService(cascadeRepository, levelRepository,
                actionRepository, draftRepository, notificationRepository,
                (cascade, action) -> { /* no-op hook */ },
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

    private UUID issued() {
        UUID draftId = inTx(() -> draftRepository.save(openDraft(UUID.randomUUID()))).getId();
        return inTx(() -> service.issue(draftId, issueCommand(key()))).getId();
    }

    private UUID issuedAndCascaded() {
        UUID id = issued();
        inTx(() -> service.cascade(id, 1, cascadeCommand(key(), L1_OWNER, L2_OWNER)));
        return id;
    }

    private List<CascadeNotification> rows(UUID cascadeId, CascadeNotificationType type) {
        return notificationRepository.findByCascadeIdAndNotificationType(cascadeId, type);
    }

    // ----------------------------------------------- same-transaction semantics

    @Test
    void outboxRows_areVisibleInsideTheActionTransaction_beforeCommit() {
        UUID id = issuedAndCascaded();

        List<CascadeNotification> visibleInTx = tx.execute(status -> {
            service.decline(id, 2, declineCommand(key(), L2_OWNER));
            // Same transaction, pre-commit: the outbox rows are already there —
            // written synchronously by the verb, not deferred to AFTER_COMMIT.
            return notificationRepository.findByCascadeIdAndNotificationType(
                    id, CascadeNotificationType.DECLINE_PENDING_ORIGINATOR);
        });
        assertThat(visibleInTx).hasSize(1);
    }

    @Test
    void actionAndOutbox_commitOrRollBackAtomically() {
        UUID id = issuedAndCascaded();
        int actionsBefore = actionRepository.findByCascadeIdOrderByChainSeqAsc(id).size();

        tx.execute(status -> {
            service.decline(id, 2, declineCommand(key(), L2_OWNER));
            status.setRollbackOnly();
            return null;
        });

        // One transaction (invariant I9): rolling it back removes the action,
        // the projections AND the notifications together — nothing partial.
        assertThat(actionRepository.findByCascadeIdOrderByChainSeqAsc(id)).hasSize(actionsBefore);
        assertThat(rows(id, CascadeNotificationType.DECLINE_PENDING_ORIGINATOR)).isEmpty();
        assertThat(rows(id, CascadeNotificationType.LEVEL_DECLINED)).isEmpty();
        Cascade after = cascadeRepository.findById(id).orElseThrow();
        assertThat(after.getChainSeq()).isEqualTo(actionsBefore);
    }

    // ----------------------------------------------------- §8 fan-out table

    @Test
    void issue_notifiesOriginatorReceipt_andActivatedL1Owner() {
        UUID id = issued();

        List<CascadeNotification> issuedRows = rows(id, CascadeNotificationType.CASCADE_ISSUED);
        assertThat(issuedRows).hasSize(1);
        assertThat(issuedRows.get(0).getRecipientLabel()).isEqualTo(ORIGINATOR);

        List<CascadeNotification> activated = rows(id, CascadeNotificationType.LEVEL_ACTIVATED);
        assertThat(activated).hasSize(1);
        assertThat(activated.get(0).getRecipientLabel()).isEqualTo(L1_OWNER);
        assertThat(activated.get(0).getDepth()).isEqualTo(1);
        assertThat(activated.get(0).getPayload().get("headline").asText()).isNotBlank();
    }

    @Test
    void cascade_notifiesNextOwnerActivation_andOriginatorVisibilityFeed() {
        UUID id = issuedAndCascaded();

        List<CascadeNotification> activated = rows(id, CascadeNotificationType.LEVEL_ACTIVATED);
        assertThat(activated).hasSize(2); // L1 at issue + L2 at cascade
        CascadeNotification l2Activation = activated.stream()
                .filter(n -> L2_OWNER.equals(n.getRecipientLabel()))
                .findFirst().orElseThrow();
        assertThat(l2Activation.getDepth()).isEqualTo(2);

        List<CascadeNotification> cascaded = rows(id, CascadeNotificationType.LEVEL_CASCADED);
        assertThat(cascaded).hasSize(1);
        assertThat(cascaded.get(0).getRecipientLabel()).isEqualTo(ORIGINATOR);
    }

    @Test
    void declineAtDepth2_notifiesResponseOwnerAndOriginator() {
        UUID id = issuedAndCascaded();
        inTx(() -> service.decline(id, 2, declineCommand(key(), L2_OWNER)));

        List<CascadeNotification> declined = rows(id, CascadeNotificationType.LEVEL_DECLINED);
        assertThat(declined).hasSize(1);
        assertThat(declined.get(0).getRecipientLabel()).isEqualTo(L1_OWNER); // the response owner

        List<CascadeNotification> originator =
                rows(id, CascadeNotificationType.DECLINE_PENDING_ORIGINATOR);
        assertThat(originator).hasSize(1);
        assertThat(originator.get(0).getRecipientLabel()).isEqualTo(ORIGINATOR);
        // Leader-attention headline, no failure vocabulary (decline ≠ error).
        assertThat(originator.get(0).getPayload().get("headline").asText())
                .startsWith("Decline pending");
    }

    @Test
    void declineAtDepth1_skipsTheLevelDeclinedRow_whenResponseOwnerWouldBeL0() {
        UUID id = issued();
        inTx(() -> service.decline(id, 1, declineCommand(key(), L1_OWNER)));

        assertThat(rows(id, CascadeNotificationType.LEVEL_DECLINED)).isEmpty(); // skip when k−1 = 0
        assertThat(rows(id, CascadeNotificationType.DECLINE_PENDING_ORIGINATOR)).hasSize(1);
    }

    @Test
    void absorbAtDepth1_notifiesOriginatorOnly() {
        UUID id = issued();
        inTx(() -> service.absorb(id, 1, absorbCommand(key(), L1_OWNER)));

        List<CascadeNotification> absorbed = rows(id, CascadeNotificationType.LEVEL_ABSORBED);
        assertThat(absorbed).hasSize(1); // k < 2 → no k−1 owner row
        assertThat(absorbed.get(0).getRecipientLabel()).isEqualTo(ORIGINATOR);
    }

    @Test
    void absorbAtDepth2_notifiesOriginatorAndTheLevelAbove() {
        UUID id = issuedAndCascaded();
        inTx(() -> service.absorb(id, 2, absorbCommand(key(), L2_OWNER)));

        List<CascadeNotification> absorbed = rows(id, CascadeNotificationType.LEVEL_ABSORBED);
        assertThat(absorbed).hasSize(2);
        assertThat(absorbed).extracting(CascadeNotification::getRecipientLabel)
                .containsExactlyInAnyOrder(ORIGINATOR, L1_OWNER);
    }

    @Test
    void recascade_notifiesTheReactivatedOwner_withTheNewRound() {
        UUID id = issuedAndCascaded();
        inTx(() -> service.decline(id, 2, declineCommand(key(), L2_OWNER)));
        inTx(() -> service.recascade(id, 1, recascadeCommand(key(), L1_OWNER, "Replacement Owner")));

        List<CascadeNotification> activated = rows(id, CascadeNotificationType.LEVEL_ACTIVATED);
        assertThat(activated).hasSize(3); // issue L1, cascade L2, recascade L2 round 2
        CascadeNotification reactivation = activated.stream()
                .filter(n -> "Replacement Owner".equals(n.getRecipientLabel()))
                .findFirst().orElseThrow();
        assertThat(reactivation.getPayload().get("headline").asText()).contains("round 2");
    }

    @Test
    void resolve_notifiesEveryDistinctLevelOwnerDepthAtLeast1_deduplicated() {
        // Build a chain where L1 and L3 share the same owner label: the
        // resolve fan-out must dedupe to distinct owners (§8).
        UUID id = issued();
        inTx(() -> service.cascade(id, 1, cascadeCommand(key(), L1_OWNER, L2_OWNER)));
        inTx(() -> service.cascade(id, 2, cascadeCommand(key(), L2_OWNER, L1_OWNER)));
        inTx(() -> service.resolve(id, resolveLooksGood(key())));

        List<CascadeNotification> resolvedRows =
                rows(id, CascadeNotificationType.CASCADE_RESOLVED_LOOKS_GOOD);
        assertThat(resolvedRows).hasSize(2); // L1_OWNER (depths 1+3 deduped) + L2_OWNER
        assertThat(resolvedRows).extracting(CascadeNotification::getRecipientLabel)
                .containsExactlyInAnyOrder(L1_OWNER, L2_OWNER);
    }

    // ------------------------------------------- crash-safety architecture assert

    @Test
    void engineCascadePackage_containsNoTransactionalEventListener() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] classes = resolver.getResources(
                "classpath*:com/meridian/substrate/engine/cascade/**/*.class");
        assertThat(classes.length)
                .as("sanity: the engine.cascade classes are on the test classpath")
                .isGreaterThanOrEqualTo(8);

        MetadataReaderFactory factory = new CachingMetadataReaderFactory(resolver);
        for (Resource resource : classes) {
            var metadata = factory.getMetadataReader(resource).getAnnotationMetadata();
            assertThat(metadata.getAnnotatedMethods(
                    "org.springframework.transaction.event.TransactionalEventListener"))
                    .as("ADR D10: no AFTER_COMMIT notification path may exist in %s",
                            metadata.getClassName())
                    .isEmpty();
        }
    }
}
