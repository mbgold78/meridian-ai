package com.meridian.substrate.app.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.app.api.v1.request.InitiativeProgressRequest;
import com.meridian.substrate.app.api.v1.request.TransformationInitiativeCreateRequest;
import com.meridian.substrate.app.api.v1.request.TransformationPlanCreateRequest;
import com.meridian.substrate.common.domain.TransformationInitiative;
import com.meridian.substrate.common.domain.TransformationPlan;
import com.meridian.substrate.common.domain.enums.InitiativeStatus;
import com.meridian.substrate.common.domain.enums.TransformationPlanStatus;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.common.repo.TransformationInitiativeRepository;
import com.meridian.substrate.common.repo.TransformationPlanRepository;
import com.meridian.substrate.engine.contract.gateway.Governed;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * D5 thin-slice transformation CRUD (engine-spec diagnostic.md §3.3/§10.1;
 * ADR-004 D13 — declared S4 deviation). Operator-grade plumbing ONLY: the
 * value sets are typed, but plan state-machine gates, wave-sequencing rules,
 * prerequisite-graph validation and the rationalization lens are deliberately
 * NOT implemented — EXTENSION POINT(diagnostic-9): the S4 planning-engine
 * design session owns those semantics (V8 amendments are flagged cheap under
 * the fresh-DB posture). The fixture {@code GET /transformation/plan} stays
 * behind its flag, untouched.
 *
 * <p>Every write verb is {@code @Governed} (ADR-004 D12; test plan #15) —
 * HUMAN persona principals via the REST lane; the §14.6 ArchUnit
 * governed-coverage rule picks this package up automatically.
 */
@Service
public class TransformationService {

    private final TransformationPlanRepository planRepository;
    private final TransformationInitiativeRepository initiativeRepository;
    private final DomainRepository domainRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TransformationService(TransformationPlanRepository planRepository,
                                 TransformationInitiativeRepository initiativeRepository,
                                 DomainRepository domainRepository,
                                 ObjectMapper objectMapper,
                                 Clock clock) {
        this.planRepository = planRepository;
        this.initiativeRepository = initiativeRepository;
        this.domainRepository = domainRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** {@code POST /transformation/plans} — status defaults DRAFT; transitions unenforced v1. */
    @Governed(actionType = "transformation_plan.create")
    @Transactional
    public TransformationPlan createPlan(TransformationPlanCreateRequest request) {
        if (!domainRepository.existsById(request.domainId())) {
            throw new EntityNotFoundException("Domain not found: " + request.domainId());
        }
        TransformationPlan plan = new TransformationPlan();
        plan.setDomainId(request.domainId());
        plan.setName(request.name());
        plan.setDescription(request.description());
        plan.setStatus(request.status() != null ? request.status() : TransformationPlanStatus.DRAFT);
        plan.setTargetHorizonMonths(request.targetHorizonMonths());
        plan.setReductionMode(request.reductionMode());
        return planRepository.save(plan);
    }

    /** {@code POST /transformation/plans/{id}/initiatives} — soft plan ref, app-enforced. */
    @Governed(actionType = "initiative.create")
    @Transactional
    public TransformationInitiative addInitiative(UUID planId,
                                                  TransformationInitiativeCreateRequest request) {
        if (!planRepository.existsById(planId)) {
            throw new EntityNotFoundException("Transformation plan not found: " + planId);
        }
        TransformationInitiative initiative = new TransformationInitiative();
        initiative.setPlanId(planId);
        initiative.setName(request.name());
        initiative.setDescription(request.description());
        initiative.setInitiativeType(request.initiativeType());
        initiative.setStatus(request.status() != null ? request.status() : InitiativeStatus.PLANNED);
        initiative.setTargetCapabilityId(request.targetCapabilityId());
        initiative.setTargetProcessId(request.targetProcessId());
        initiative.setTargetSystemId(request.targetSystemId());
        initiative.setWave(request.wave());
        initiative.setOwnerName(request.ownerName());
        initiative.setEstimatedMonths(request.estimatedMonths());
        initiative.setComplexity(request.complexity());
        initiative.setAnnualSavingsUsd(request.annualSavingsUsd());
        initiative.setPrerequisites(uuidArray(request.prerequisites()));
        initiative.setEvidenceIds(uuidArray(request.evidenceIds()));
        if (request.progressPct() != null) {
            initiative.setProgressPct(request.progressPct());
            initiative.setProgressUpdatedAt(clock.instant());
        }
        initiative.setProgressNote(request.progressNote());
        return initiativeRepository.save(initiative);
    }

    /**
     * {@code PATCH /initiatives/{id}/progress} (spec §10.1/§12.5) — the named
     * D12 verb. Records the operator's number + note and stamps
     * {@code progressUpdatedAt} from the shared engine clock (S15); staleness
     * of that stamp is surfaced in the read DTO, never hidden.
     */
    @Governed(actionType = "initiative.update_progress")
    @Transactional
    public TransformationInitiative updateProgress(UUID initiativeId,
                                                   InitiativeProgressRequest request) {
        TransformationInitiative initiative = initiativeRepository.findById(initiativeId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Transformation initiative not found: " + initiativeId));
        initiative.setProgressPct(request.progressPct());
        if (request.progressNote() != null) {
            initiative.setProgressNote(request.progressNote());
        }
        initiative.setProgressUpdatedAt(clock.instant());
        return initiativeRepository.save(initiative);
    }

    private com.fasterxml.jackson.databind.JsonNode uuidArray(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        var array = objectMapper.createArrayNode();
        ids.forEach(id -> array.add(id.toString()));
        return array;
    }
}
