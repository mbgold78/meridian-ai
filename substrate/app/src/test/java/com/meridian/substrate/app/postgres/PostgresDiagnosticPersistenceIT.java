package com.meridian.substrate.app.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.AiCurationEvent;
import com.meridian.substrate.common.domain.DiagnosticRunBatch;
import com.meridian.substrate.common.domain.Finding;
import com.meridian.substrate.common.domain.enums.CurationAction;
import com.meridian.substrate.common.domain.enums.CurationStatus;
import com.meridian.substrate.common.domain.enums.CurationSubjectKind;
import com.meridian.substrate.common.domain.enums.FindingType;
import com.meridian.substrate.common.domain.enums.Severity;
import com.meridian.substrate.common.repo.AiCurationEventRepository;
import com.meridian.substrate.common.repo.DiagnosticRunBatchRepository;
import com.meridian.substrate.common.repo.FindingRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Diagnostic test plan #16 + the V7_1 DB-backstop pins (engine-spec
 * diagnostic.md §3.1/§3.2/§14; ADR-004 D4/D11) against real Postgres 16
 * under {@code ddl-auto=validate}:
 *
 * <ol>
 *   <li>Round-trip of every new/altered V7 entity surface — the four
 *       Finding curation columns, {@link AiCurationEvent},
 *       {@link DiagnosticRunBatch} — incl. TIMESTAMPTZ + TEXT-backed JSON
 *       (port-map traps #1/#4);</li>
 *   <li>the ai_curation_events append-only trigger raises on direct JDBC
 *       UPDATE and DELETE (PG-only; H2 dev is service-enforced — H2-green is
 *       never acceptance);</li>
 *   <li>the one-active-diagnostic-run partial unique index fires for a
 *       second PLANNED/RUNNING diagnostic run in the same domain and ONLY
 *       then (terminal runs and other run types coexist);</li>
 *   <li>the uq_curation_idem idempotency backstop.</li>
 * </ol>
 *
 * <p>Self-disables without Docker ({@code disabledWithoutDocker = true});
 * compiles locally on the Docker-less dev machine, runs in CI — claiming
 * local verification of the PG-only behaviors would be a false-PASS.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class PostgresDiagnosticPersistenceIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private FindingRepository findingRepository;
    @Autowired private AiCurationEventRepository curationEventRepository;
    @Autowired private DiagnosticRunBatchRepository batchRepository;

    // ----------------------------------------------- 1. V7 entity round-trip

    @Test
    @Transactional
    void v7EntitiesRoundTripOnPostgres() {
        UUID domainId = UUID.randomUUID();
        UUID assertionId = UUID.randomUUID();
        UUID synthesisRunId = UUID.randomUUID();

        Finding finding = new Finding();
        finding.setDomainId(domainId);
        finding.setFindingType(FindingType.DATA_QUALITY);
        finding.setSeverity(Severity.HIGH);
        finding.setTitle("V7 round-trip");
        finding.setDescription("curation projection columns");
        finding.setAiGenerated(true);
        finding.setSynthesisRunId(synthesisRunId);
        finding.setAssertionId(assertionId);
        finding.setCurationStatus(CurationStatus.PENDING_REVIEW);
        finding.setCuratedAt(Instant.parse("2026-06-10T12:00:00Z"));
        finding.setCuratedByLabel("Riley Operator");
        finding.setMetadata(json("{\"groundingMode\":\"EXISTENCE_ONLY\",\"candidateType\":\"CONTENT\"}"));
        em.persist(finding);

        AiCurationEvent event = new AiCurationEvent();
        event.setDomainId(domainId);
        event.setSubjectKind(CurationSubjectKind.FINDING);
        event.setSubjectId(finding.getId());
        event.setAction(CurationAction.REJECT);
        event.setRationale("evidence does not support the claim");
        event.setOverriddenFields(json("{\"name\":\"edited\"}"));
        event.setSynthesisRunId(synthesisRunId);
        event.setActorLabel("Riley Operator");
        event.setActorPersonaId("L3_ANALYST");
        event.setIdempotencyKey("it-key-1");
        em.persist(event);

        DiagnosticRunBatch batch = new DiagnosticRunBatch();
        batch.setSynthesisRunId(synthesisRunId);
        batch.setBatchNo(1);
        batch.setScope("systems");
        batch.setTargetEntityIds(json("[\"" + UUID.randomUUID() + "\"]"));
        batch.setStatus(DiagnosticRunBatch.STATUS_COMPLETED);
        batch.setAttempt(2);
        batch.setExecutor("in-process");
        batch.setCandidatesReturned(9);
        batch.setCandidatesFiltered(2);
        batch.setFindingsCommitted(7);
        batch.setFilterHistogram(json("{\"badType\":1,\"ungrounded\":1}"));
        batch.setDispatchTaskId("disp-42");
        batch.setClaimedAt(Instant.parse("2026-06-10T08:00:00Z"));
        batch.setStartedAt(Instant.parse("2026-06-10T08:00:01Z"));
        batch.setFinishedAt(Instant.parse("2026-06-10T08:02:00Z"));
        em.persist(batch);

        em.flush();
        em.clear();

        Finding dbFinding = em.find(Finding.class, finding.getId());
        assertThat(dbFinding.getAssertionId()).isEqualTo(assertionId);
        assertThat(dbFinding.getCurationStatus()).isEqualTo(CurationStatus.PENDING_REVIEW);
        assertThat(dbFinding.getCuratedAt()).isEqualTo(Instant.parse("2026-06-10T12:00:00Z"));
        assertThat(dbFinding.getCuratedByLabel()).isEqualTo("Riley Operator");

        AiCurationEvent dbEvent = curationEventRepository.findById(event.getId()).orElseThrow();
        assertThat(dbEvent.getSubjectKind()).isEqualTo(CurationSubjectKind.FINDING);
        assertThat(dbEvent.getAction()).isEqualTo(CurationAction.REJECT);
        assertThat(dbEvent.getOverriddenFields()).isEqualTo(json("{\"name\":\"edited\"}"));
        assertThat(dbEvent.getIdempotencyKey()).isEqualTo("it-key-1");

        DiagnosticRunBatch dbBatch = em.find(DiagnosticRunBatch.class, batch.getId());
        assertThat(dbBatch.getStatus()).isEqualTo(DiagnosticRunBatch.STATUS_COMPLETED);
        assertThat(dbBatch.getAttempt()).isEqualTo(2);
        assertThat(dbBatch.getFilterHistogram()).isEqualTo(json("{\"badType\":1,\"ungrounded\":1}"));
        assertThat(dbBatch.getClaimedAt()).isEqualTo(Instant.parse("2026-06-10T08:00:00Z"));

        // dialect-sensitive derived queries on PG
        assertThat(findingRepository.findByDomainIdAndAiGeneratedTrue(domainId)).hasSize(1);
        assertThat(curationEventRepository
                .findBySubjectKindAndSubjectIdOrderByCreatedAtAsc(CurationSubjectKind.FINDING, finding.getId()))
                .hasSize(1);
        assertThat(curationEventRepository
                .findBySubjectKindAndSubjectIdAndIdempotencyKey(
                        CurationSubjectKind.FINDING, finding.getId(), "it-key-1"))
                .isPresent();
        assertThat(batchRepository.findBySynthesisRunIdOrderByBatchNoAsc(synthesisRunId))
                .extracting(DiagnosticRunBatch::getBatchNo).containsExactly(1);
    }

    // ------------------------------------- 2. append-only trigger (PG only)

    @Test
    void curationEventUpdateAndDeleteRaiseViaTrigger() {
        UUID eventId = insertCurationEvent(UUID.randomUUID(), UUID.randomUUID(), "trig-key");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE ai_curation_events SET rationale = 'tampered' WHERE id = ?", eventId))
                .as("V7_1 trigger raises on UPDATE")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ai_curation_events is append-only");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM ai_curation_events WHERE id = ?", eventId))
                .as("V7_1 trigger raises on DELETE")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ai_curation_events is append-only");

        Integer survivors = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_curation_events WHERE id = ?", Integer.class, eventId);
        assertThat(survivors).isEqualTo(1);
    }

    // --------------------------- 3. one-active-diagnostic-run partial unique

    @Test
    void secondActiveDiagnosticRunPerDomainRejectedByPartialIndex() {
        UUID domainId = UUID.randomUUID();

        insertSynthesisRun(domainId, "diagnostic", "PLANNED");   // first active — fine
        insertSynthesisRun(domainId, "diagnostic", "COMPLETED"); // terminal coexists — index is partial
        insertSynthesisRun(domainId, "validative", "RUNNING");   // other run types unaffected
        insertSynthesisRun(UUID.randomUUID(), "diagnostic", "RUNNING"); // other domains unaffected

        assertThatThrownBy(() -> insertSynthesisRun(domainId, "diagnostic", "RUNNING"))
                .as("second PLANNED/RUNNING diagnostic run in the same domain violates the partial unique index")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_active_diagnostic_run_per_domain");
    }

    // ------------------------------------------- 4. idempotency-key backstop

    @Test
    void duplicateCurationIdempotencyKeyRejected() {
        UUID domainId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();
        insertCurationEvent(domainId, subjectId, "same-key");

        assertThatThrownBy(() -> insertCurationEvent(domainId, subjectId, "same-key"))
                .as("uq_curation_idem backstops idempotency replay")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_curation_idem");
    }

    // -------------------------------------------------------------- helpers

    private UUID insertCurationEvent(UUID domainId, UUID subjectId, String idempotencyKey) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO ai_curation_events (id, version, created_at, updated_at, created_by, "
                        + "domain_id, subject_kind, subject_id, action, rationale, actor_label, "
                        + "actor_persona_id, idempotency_key) "
                        + "VALUES (?, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'diag-it', ?, "
                        + "'FINDING', ?, 'REJECT', 'it rationale', 'Riley Operator', 'L3_ANALYST', ?)",
                id, domainId, subjectId, idempotencyKey);
        return id;
    }

    private void insertSynthesisRun(UUID domainId, String runType, String status) {
        jdbcTemplate.update(
                "INSERT INTO synthesis_runs (id, domain_id, run_type, status, started_at, "
                        + "created_at, updated_at, created_by, version) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, "
                        + "CURRENT_TIMESTAMP, 'diag-it', 0)",
                UUID.randomUUID(), domainId, runType, status);
    }

    private static JsonNode json(String content) {
        try {
            return MAPPER.readTree(content);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Bad test JSON literal: " + content, e);
        }
    }
}
