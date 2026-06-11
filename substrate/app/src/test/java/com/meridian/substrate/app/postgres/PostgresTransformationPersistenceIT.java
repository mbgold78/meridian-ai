package com.meridian.substrate.app.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.TransformationInitiative;
import com.meridian.substrate.common.domain.TransformationPlan;
import com.meridian.substrate.common.domain.enums.InitiativeComplexity;
import com.meridian.substrate.common.domain.enums.InitiativeStatus;
import com.meridian.substrate.common.domain.enums.InitiativeType;
import com.meridian.substrate.common.domain.enums.ReductionMode;
import com.meridian.substrate.common.domain.enums.TransformationPlanStatus;
import com.meridian.substrate.common.repo.TransformationInitiativeRepository;
import com.meridian.substrate.common.repo.TransformationPlanRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D5 test plan #16 — V8 round-trip against real Postgres 16 under
 * {@code ddl-auto=validate} (engine-spec diagnostic.md §3.3/§14; ADR-004
 * D13): {@link TransformationPlan} + {@link TransformationInitiative}
 * persist and read back with TIMESTAMPTZ semantics (port-map traps #1/#4)
 * and TEXT-backed JSON UUID arrays; the soft plan→initiative ref resolves
 * through the repository surface the read service composes on.
 *
 * <p>Self-disables without Docker ({@code disabledWithoutDocker = true});
 * compiles locally on the Docker-less dev machine, RUNS IN CI — H2-green is
 * never acceptance.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class PostgresTransformationPersistenceIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private EntityManager em;
    @Autowired private TransformationPlanRepository planRepository;
    @Autowired private TransformationInitiativeRepository initiativeRepository;

    @Test
    @Transactional
    void v8EntitiesRoundTripOnPostgres() {
        UUID domainId = UUID.randomUUID();
        Instant approvedAt = Instant.parse("2026-06-10T17:30:00.123456Z");

        TransformationPlan plan = new TransformationPlan();
        plan.setDomainId(domainId);
        plan.setName("Glacier rationalization");
        plan.setDescription("V8 thin-slice round-trip");
        plan.setStatus(TransformationPlanStatus.APPROVED);
        plan.setTargetHorizonMonths(18);
        plan.setReductionMode(ReductionMode.BALANCED);
        plan.setApprovedBy("operator:mike");
        plan.setApprovedAt(approvedAt);
        UUID planId = planRepository.save(plan).getId();

        UUID prereq = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        Instant progressAt = Instant.parse("2026-06-11T08:00:00Z");

        TransformationInitiative initiative = new TransformationInitiative();
        initiative.setPlanId(planId);
        initiative.setName("Retire legacy ledger bridge");
        initiative.setDescription("first wave");
        initiative.setInitiativeType(InitiativeType.SYSTEM_RETIRE);
        initiative.setStatus(InitiativeStatus.IN_PROGRESS);
        initiative.setTargetSystemId(UUID.randomUUID());
        initiative.setWave(1);
        initiative.setOwnerName("Ops Lead");
        initiative.setEstimatedMonths(6);
        initiative.setComplexity(InitiativeComplexity.HIGH);
        initiative.setAnnualSavingsUsd(180_000L);
        initiative.setPrerequisites(MAPPER.createArrayNode().add(prereq.toString()));
        initiative.setEvidenceIds(MAPPER.createArrayNode().add(evidenceId.toString()));
        initiative.setProgressPct(35);
        initiative.setProgressNote("vendor contract notice served");
        initiative.setProgressUpdatedAt(progressAt);
        UUID initiativeId = initiativeRepository.save(initiative).getId();

        em.flush();
        em.clear();

        TransformationPlan reloadedPlan = planRepository.findById(planId).orElseThrow();
        assertThat(reloadedPlan.getStatus()).isEqualTo(TransformationPlanStatus.APPROVED);
        assertThat(reloadedPlan.getReductionMode()).isEqualTo(ReductionMode.BALANCED);
        // TIMESTAMPTZ semantics: PG stores microsecond precision — compare at micros.
        assertThat(reloadedPlan.getApprovedAt())
                .isEqualTo(approvedAt.truncatedTo(ChronoUnit.MICROS));
        assertThat(reloadedPlan.getCreatedAt()).isNotNull();
        assertThat(reloadedPlan.getVersion()).isNotNull();

        List<TransformationPlan> byDomain =
                planRepository.findByDomainIdOrderByCreatedAtAsc(domainId);
        assertThat(byDomain).extracting(TransformationPlan::getId).containsExactly(planId);

        TransformationInitiative reloaded =
                initiativeRepository.findById(initiativeId).orElseThrow();
        assertThat(reloaded.getInitiativeType()).isEqualTo(InitiativeType.SYSTEM_RETIRE);
        assertThat(reloaded.getStatus()).isEqualTo(InitiativeStatus.IN_PROGRESS);
        assertThat(reloaded.getComplexity()).isEqualTo(InitiativeComplexity.HIGH);
        assertThat(reloaded.getAnnualSavingsUsd()).isEqualTo(180_000L);
        assertThat(reloaded.getProgressPct()).isEqualTo(35);
        assertThat(reloaded.getProgressUpdatedAt())
                .isEqualTo(progressAt.truncatedTo(ChronoUnit.MICROS));
        // TEXT-backed JSON arrays survive the round-trip verbatim (§4.5 posture).
        assertThat(reloaded.getPrerequisites().get(0).asText()).isEqualTo(prereq.toString());
        assertThat(reloaded.getEvidenceIds().get(0).asText()).isEqualTo(evidenceId.toString());

        // The soft plan→initiative ref the read service composes on (§12.7).
        assertThat(initiativeRepository.findByPlanIdInOrderByCreatedAtAsc(List.of(planId)))
                .extracting(TransformationInitiative::getId)
                .containsExactly(initiativeId);
    }
}
