package com.meridian.substrate.app.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.Cascade;
import com.meridian.substrate.common.domain.CascadeDraft;
import com.meridian.substrate.common.domain.CascadeLevel;
import com.meridian.substrate.common.domain.CascadeLevelAction;
import com.meridian.substrate.common.domain.CascadeNotification;
import com.meridian.substrate.common.domain.enums.CascadeActionType;
import com.meridian.substrate.common.domain.enums.CascadeAttentionSignal;
import com.meridian.substrate.common.domain.enums.CascadeAuthoringMode;
import com.meridian.substrate.common.domain.enums.CascadeDraftStatus;
import com.meridian.substrate.common.domain.enums.CascadeLevelState;
import com.meridian.substrate.common.domain.enums.CascadeNotificationType;
import com.meridian.substrate.common.domain.enums.CascadeState;
import com.meridian.substrate.common.domain.enums.CascadeSuggestionState;
import com.meridian.substrate.common.domain.enums.ProducedBy;
import com.meridian.substrate.common.repo.CascadeDraftRepository;
import com.meridian.substrate.common.repo.CascadeLevelActionRepository;
import com.meridian.substrate.common.repo.CascadeLevelRepository;
import com.meridian.substrate.common.repo.CascadeNotificationRepository;
import com.meridian.substrate.common.repo.CascadeRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cascade engine Step-1 Postgres gate (engine-spec cascade.md §12 test plan
 * #12 + the SQL-level half of #6): V4 + V4_1 apply on real Postgres 16, all
 * five cascade entities persist/read under {@code ddl-auto=validate}, the
 * append-only trigger raises on UPDATE/DELETE of {@code cascade_level_actions}
 * (ADR D9), TIMESTAMPTZ round-trips, and UNIQUE-with-NULL {@code dedupe_key}
 * semantics hold (port-map traps #1, #4).
 *
 * <p><b>Docker-less machines (e.g. the Windows dev box):</b> self-disables via
 * {@code @Testcontainers(disabledWithoutDocker = true)}; CI (ubuntu-latest)
 * runs the real gate. H2-green is never acceptance (port-map §4.4).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class PostgresCascadePersistenceIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private CascadeRepository cascadeRepository;
    @Autowired private CascadeLevelRepository cascadeLevelRepository;
    @Autowired private CascadeLevelActionRepository cascadeLevelActionRepository;
    @Autowired private CascadeDraftRepository cascadeDraftRepository;
    @Autowired private CascadeNotificationRepository cascadeNotificationRepository;

    // -----------------------------------------------------------------------
    // 1. Flyway: V4 (common) + V4_1 (postgresql vendor dir) both apply
    // -----------------------------------------------------------------------

    @Test
    void flywayAppliesV4AndPostgresAppendOnlyTriggerMigration() {
        List<Map<String, Object>> history = jdbcTemplate.queryForList(
                "SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");

        assertThat(history)
                .extracting(row -> String.valueOf(row.get("version")))
                .contains("4", "4.1");
        assertThat(history)
                .allSatisfy(row -> assertThat(row.get("success"))
                        .as("migration V%s succeeded", row.get("version"))
                        .isEqualTo(Boolean.TRUE));

        Integer triggerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_trigger WHERE tgname = 'trg_cla_append_only'",
                Integer.class);
        assertThat(triggerCount)
                .as("V4_1 append-only trigger present on cascade_level_actions")
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // 2. All five cascade entities: persist → flush → clear → reload
    //    under ddl-auto=validate (spec §12 test #12)
    // -----------------------------------------------------------------------

    @Test
    @Transactional
    void allFiveCascadeEntitiesRoundTripOnPostgres() {
        UUID domainId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();

        CascadeDraft draft = new CascadeDraft();
        draft.setDomainId(domainId);
        draft.setAuthorLabel("Operating Partner");
        draft.setOriginalCritique("Vendor cost growth is outpacing volume.");
        draft.setAcceptanceCriteria("A plan, or a Decline with documented reasoning.");
        draft.setSummary("Vendor cost discipline");
        draft.setCascadeGroupId(groupId);
        draft.setAuthoringMode(CascadeAuthoringMode.FREE_FORM);
        draft.setResponseSlaDays(0);
        draft.setSuggestionProvenance(json(
                "{\"taskId\":\"t-1\",\"model\":\"mock\",\"eventsDigest\":\"d\",\"requestedAt\":\"2026-04-07T00:00:00Z\",\"landedAt\":\"2026-04-07T00:01:00Z\"}"));
        draft.setSuggestionState(CascadeSuggestionState.NONE);
        draft.setStatus(CascadeDraftStatus.OPEN);
        em.persist(draft);

        Cascade cascade = new Cascade();
        cascade.setDomainId(domainId);
        cascade.setCascadeGroupId(groupId);
        cascade.setOriginOwnerLabel("Operating Partner");
        cascade.setOriginatedAt(Instant.parse("2026-04-08T00:00:00Z"));
        cascade.setOriginalCritique("Vendor cost growth is outpacing volume.");
        cascade.setAcceptanceCriteria("A plan, or a Decline with documented reasoning.");
        cascade.setSummary("Vendor cost discipline");
        cascade.setAuthoringMode(CascadeAuthoringMode.FREE_FORM);
        cascade.setProducedBy(ProducedBy.HUMAN);
        cascade.setDraftId(draft.getId());
        cascade.setResponseSlaDays(0);
        cascade.setCurrentState(CascadeState.IN_FLIGHT);
        cascade.setChainSeq(1);
        cascade.setFrontierDepth(1);
        cascade.setFrontierState(CascadeLevelState.LOOKING);
        cascade.setAttentionSignal(CascadeAttentionSignal.ESCALATING);
        em.persist(cascade);

        CascadeLevel level0 = new CascadeLevel();
        level0.setCascadeId(cascade.getId());
        level0.setDepth(0);
        level0.setOwnerLabel("Operating Partner");
        level0.setCurrentState(CascadeLevelState.CASCADED);
        level0.setRefinedCritique("Vendor cost growth is outpacing volume.");
        level0.setActivatedAt(Instant.parse("2026-04-08T00:00:00Z"));
        level0.setActedAt(Instant.parse("2026-04-08T00:00:00Z"));
        em.persist(level0);

        CascadeLevel level1 = new CascadeLevel();
        level1.setCascadeId(cascade.getId());
        level1.setDepth(1);
        level1.setOwnerLabel("Curasync COO");
        level1.setCurrentState(CascadeLevelState.LOOKING);
        level1.setActivatedAt(Instant.parse("2026-04-08T00:00:00Z"));
        em.persist(level1);

        CascadeLevelAction issue = new CascadeLevelAction();
        issue.setCascadeId(cascade.getId());
        issue.setLevelId(level0.getId());
        issue.setDepth(0);
        issue.setRound(1);
        issue.setAction(CascadeActionType.ISSUE);
        issue.setActorLabel("Operating Partner");
        issue.setRefinedCritique("Vendor cost growth is outpacing volume.");
        issue.setNextOwnerLabel("Curasync COO");
        issue.setActedAt(Instant.parse("2026-04-08T00:00:00Z"));
        issue.setIdempotencyKey("it-issue-1");
        issue.setChainSeq(1);
        cascadeLevelActionRepository.save(issue);

        CascadeNotification notification = new CascadeNotification();
        notification.setCascadeId(cascade.getId());
        notification.setActionId(issue.getId());
        notification.setDepth(1);
        notification.setNotificationType(CascadeNotificationType.LEVEL_ACTIVATED);
        notification.setRecipientLabel("Curasync COO");
        notification.setPayload(json("{\"cascadeId\":\"" + cascade.getId()
                + "\",\"depth\":1,\"round\":1,\"headline\":\"New cascade level\"}"));
        notification.setDedupeKey("it:activated:" + cascade.getId() + ":1:1");
        em.persist(notification);

        em.flush();
        em.clear();

        Cascade dbCascade = em.find(Cascade.class, cascade.getId());
        assertThat(dbCascade.getDomainId()).isEqualTo(domainId);
        assertThat(dbCascade.getCascadeGroupId()).isEqualTo(groupId);
        assertThat(dbCascade.getOriginatedAt()).isEqualTo(Instant.parse("2026-04-08T00:00:00Z"));
        assertThat(dbCascade.getOriginalCritique()).isEqualTo("Vendor cost growth is outpacing volume.");
        assertThat(dbCascade.getAcceptanceCriteria()).isEqualTo("A plan, or a Decline with documented reasoning.");
        assertThat(dbCascade.getAuthoringMode()).isEqualTo(CascadeAuthoringMode.FREE_FORM);
        assertThat(dbCascade.getProducedBy()).isEqualTo(ProducedBy.HUMAN);
        assertThat(dbCascade.getDraftId()).isEqualTo(draft.getId());
        assertThat(dbCascade.getResponseSlaDays()).isZero();
        assertThat(dbCascade.getCurrentState()).isEqualTo(CascadeState.IN_FLIGHT);
        assertThat(dbCascade.getChainSeq()).isEqualTo(1);
        assertThat(dbCascade.getFrontierDepth()).isEqualTo(1);
        assertThat(dbCascade.getFrontierState()).isEqualTo(CascadeLevelState.LOOKING);
        assertThat(dbCascade.getAttentionSignal()).isEqualTo(CascadeAttentionSignal.ESCALATING);
        assertThat(dbCascade.isOverdue()).isFalse();
        assertThat(dbCascade.getResolvedAt()).isNull();
        assertThat(dbCascade.getVersion()).isZero();
        assertThat(dbCascade.getCreatedBy()).isEqualTo("system");

        CascadeLevel dbLevel1 = em.find(CascadeLevel.class, level1.getId());
        assertThat(dbLevel1.getDepth()).isEqualTo(1);
        assertThat(dbLevel1.getRound()).isEqualTo(1);
        assertThat(dbLevel1.getCurrentState()).isEqualTo(CascadeLevelState.LOOKING);
        assertThat(dbLevel1.getActivatedAt()).isEqualTo(Instant.parse("2026-04-08T00:00:00Z"));
        assertThat(dbLevel1.getResponseDeadline()).isNull();
        assertThat(dbLevel1.isOverdue()).isFalse();

        CascadeLevelAction dbIssue = cascadeLevelActionRepository.findById(issue.getId()).orElseThrow();
        assertThat(dbIssue.getAction()).isEqualTo(CascadeActionType.ISSUE);
        assertThat(dbIssue.getActorLabel()).isEqualTo("Operating Partner");
        assertThat(dbIssue.getNextOwnerLabel()).isEqualTo("Curasync COO");
        assertThat(dbIssue.getIdempotencyKey()).isEqualTo("it-issue-1");
        assertThat(dbIssue.getChainSeq()).isEqualTo(1);
        assertThat(dbIssue.getActedAt()).isEqualTo(Instant.parse("2026-04-08T00:00:00Z"));

        CascadeDraft dbDraft = em.find(CascadeDraft.class, draft.getId());
        assertThat(dbDraft.getAuthoringMode()).isEqualTo(CascadeAuthoringMode.FREE_FORM);
        assertThat(dbDraft.getStatus()).isEqualTo(CascadeDraftStatus.OPEN);
        assertThat(dbDraft.getSuggestionState()).isEqualTo(CascadeSuggestionState.NONE);
        assertThat(dbDraft.getSuggestionProvenance()).isEqualTo(json(
                "{\"taskId\":\"t-1\",\"model\":\"mock\",\"eventsDigest\":\"d\",\"requestedAt\":\"2026-04-07T00:00:00Z\",\"landedAt\":\"2026-04-07T00:01:00Z\"}"));

        CascadeNotification dbNotification = em.find(CascadeNotification.class, notification.getId());
        assertThat(dbNotification.getNotificationType()).isEqualTo(CascadeNotificationType.LEVEL_ACTIVATED);
        assertThat(dbNotification.getPayload().get("depth").asInt()).isEqualTo(1);
        assertThat(dbNotification.getReadAt()).isNull();
        assertThat(dbNotification.getDispatchedAt()).isNull();
    }

    // -----------------------------------------------------------------------
    // 3. Repository queries — derived + PESSIMISTIC_WRITE on PG
    // -----------------------------------------------------------------------

    @Test
    @Transactional
    void cascadeRepositoryQueriesAndPessimisticLockExecuteOnPostgres() {
        UUID domainId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();

        Cascade cascade = cascadeRepository.save(newCascade(domainId, groupId));

        CascadeDraft draft = new CascadeDraft();
        draft.setDomainId(domainId);
        draft.setAuthorLabel("Operating Partner");
        draft.setAuthoringMode(CascadeAuthoringMode.IMPERATIVE);
        cascadeDraftRepository.save(draft);

        CascadeNotification notification = new CascadeNotification();
        notification.setCascadeId(cascade.getId());
        notification.setNotificationType(CascadeNotificationType.CASCADE_ISSUED);
        notification.setRecipientLabel("Operating Partner");
        notification.setRecipientMemberId(UUID.randomUUID());
        cascadeNotificationRepository.save(notification);

        em.flush();
        em.clear();

        assertThat(cascadeRepository.findByDomainId(domainId)).hasSize(1);
        assertThat(cascadeRepository.findByDomainIdAndCurrentState(domainId, CascadeState.IN_FLIGHT)).hasSize(1);
        assertThat(cascadeRepository.findByDomainIdAndCurrentState(domainId, CascadeState.RESOLVED_DECLINED)).isEmpty();
        assertThat(cascadeRepository.findByCascadeGroupIdAndCurrentState(groupId, CascadeState.IN_FLIGHT)).hasSize(1);
        // SELECT … FOR UPDATE — the per-cascade serialization point (ADR D8)
        assertThat(cascadeRepository.findByIdForUpdate(cascade.getId())).isPresent();

        assertThat(cascadeDraftRepository.findByDomainIdAndStatus(domainId, CascadeDraftStatus.OPEN)).hasSize(1);
        assertThat(cascadeDraftRepository.findByIdForUpdate(draft.getId())).isPresent();

        assertThat(cascadeNotificationRepository
                .findByRecipientMemberIdAndReadAtIsNullOrderByCreatedAtDesc(notification.getRecipientMemberId()))
                .hasSize(1);
        assertThat(cascadeNotificationRepository.findByCascadeIdOrderByCreatedAtAsc(cascade.getId())).hasSize(1);
        assertThat(cascadeNotificationRepository
                .findByCascadeIdAndNotificationType(cascade.getId(), CascadeNotificationType.CASCADE_ISSUED))
                .hasSize(1);
        assertThat(cascadeNotificationRepository.existsByDedupeKey("no-such-key")).isFalse();
    }

    // -----------------------------------------------------------------------
    // 4. Append-only trigger (V4_1, ADR D9): direct JDBC UPDATE/DELETE raises
    //    (spec §12 test #6, SQL half — the repository surface has no mutators
    //    by construction: CascadeLevelActionRepository extends Repository)
    // -----------------------------------------------------------------------

    @Test
    void appendOnlyTriggerRejectsUpdateAndDeleteOnActionLog() {
        UUID cascadeId = UUID.randomUUID();
        UUID actionId = insertActionRow(cascadeId, "trigger-test-key", 1);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE cascade_level_actions SET actor_label = 'tampered' WHERE id = ?", actionId))
                .as("UPDATE on cascade_level_actions raises from trg_cla_append_only")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM cascade_level_actions WHERE id = ?", actionId))
                .as("DELETE on cascade_level_actions raises from trg_cla_append_only")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        Integer survives = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cascade_level_actions WHERE id = ?", Integer.class, actionId);
        assertThat(survives).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // 5. UNIQUE semantics: idempotency backstop + NULL dedupe_key rows
    //    (port-map trap #4; ADR D8)
    // -----------------------------------------------------------------------

    @Test
    void idempotencyKeyUniquePerCascadeIsBackstoppedByConstraint() {
        UUID cascadeId = UUID.randomUUID();
        insertActionRow(cascadeId, "same-key", 1);

        assertThatThrownBy(() -> insertActionRow(cascadeId, "same-key", 2))
                .as("duplicate (cascade_id, idempotency_key) violates uq_cla_idem")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_cla_idem");
    }

    @Test
    void dedupeKeyUniqueAllowsMultipleNullsAndRejectsDuplicateKeys() {
        UUID cascadeId = UUID.randomUUID();
        // Multiple NULL dedupe_key rows are legal (NULLs distinct under UNIQUE)
        insertNotificationRow(cascadeId, null);
        insertNotificationRow(cascadeId, null);

        String key = "it-dedupe:" + cascadeId;
        insertNotificationRow(cascadeId, key);
        assertThatThrownBy(() -> insertNotificationRow(cascadeId, key))
                .as("duplicate non-NULL dedupe_key violates uq_cn_dedupe")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_cn_dedupe");

        Integer nullRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cascade_notifications WHERE cascade_id = ? AND dedupe_key IS NULL",
                Integer.class, cascadeId);
        assertThat(nullRows).isEqualTo(2);
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private Cascade newCascade(UUID domainId, UUID groupId) {
        Cascade cascade = new Cascade();
        cascade.setDomainId(domainId);
        cascade.setCascadeGroupId(groupId);
        cascade.setOriginOwnerLabel("Operating Partner");
        cascade.setOriginatedAt(Instant.parse("2026-04-08T00:00:00Z"));
        cascade.setOriginalCritique("Original critique");
        cascade.setAcceptanceCriteria("Acceptance criteria");
        cascade.setAuthoringMode(CascadeAuthoringMode.FREE_FORM);
        cascade.setProducedBy(ProducedBy.HUMAN);
        cascade.setCurrentState(CascadeState.IN_FLIGHT);
        cascade.setChainSeq(1);
        cascade.setFrontierDepth(1);
        cascade.setFrontierState(CascadeLevelState.LOOKING);
        cascade.setAttentionSignal(CascadeAttentionSignal.ESCALATING);
        return cascade;
    }

    /** Raw insert satisfying the I4 CHECK constraints for an ISSUE action. */
    private UUID insertActionRow(UUID cascadeId, String idempotencyKey, int chainSeq) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO cascade_level_actions (id, cascade_id, level_id, depth, round, action, "
                        + "actor_label, refined_critique, next_owner_label, acted_at, idempotency_key, chain_seq, "
                        + "created_at, updated_at, created_by, version) "
                        + "VALUES (?, ?, ?, 0, 1, 'ISSUE', 'Operating Partner', 'Refined critique', 'Curasync COO', "
                        + "CURRENT_TIMESTAMP, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'cascade-it', 0)",
                id, cascadeId, UUID.randomUUID(), idempotencyKey, chainSeq);
        return id;
    }

    private void insertNotificationRow(UUID cascadeId, String dedupeKey) {
        jdbcTemplate.update(
                "INSERT INTO cascade_notifications (id, cascade_id, notification_type, recipient_label, "
                        + "dedupe_key, created_at, updated_at, created_by, version) "
                        + "VALUES (?, ?, 'CASCADE_ISSUED', 'Operating Partner', ?, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'cascade-it', 0)",
                UUID.randomUUID(), cascadeId, dedupeKey);
    }

    private static JsonNode json(String content) {
        try {
            return MAPPER.readTree(content);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Bad test JSON literal: " + content, e);
        }
    }
}
