package com.meridian.substrate.app.core.service;

import com.meridian.substrate.app.api.v1.request.ProcessStepCreateRequest;
import com.meridian.substrate.app.core.exception.EntityNotFoundException;
import com.meridian.substrate.common.domain.ProcessStep;
import com.meridian.substrate.common.repo.ProcessStepRepository;
import com.meridian.substrate.engine.contract.gateway.Governed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * CRUD for ProcessStep entities, scoped to a parent process. Phase 1 scope:
 * plain create/read/update/delete. No aggregation logic, no agent-readiness
 * scoring (that lives in Phase 3's readiness lens).
 */
@Service
public class ProcessStepService {

    private static final Logger log = LoggerFactory.getLogger(ProcessStepService.class);

    private final ProcessStepRepository repository;

    public ProcessStepService(ProcessStepRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<ProcessStep> listByProcess(UUID processId) {
        return repository.findByProcessIdOrderBySequenceOrderAsc(processId);
    }

    @Transactional(readOnly = true)
    public ProcessStep get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("ProcessStep", id));
    }

    @Governed(actionType = "process_step.create")
    @Transactional
    public ProcessStep create(UUID processId, ProcessStepCreateRequest req) {
        ProcessStep step = new ProcessStep();
        step.setProcessId(processId);
        int order = req.sequenceOrder() == null
                ? repository.findByProcessIdOrderBySequenceOrderAsc(processId).size()
                : req.sequenceOrder();
        step.setSequenceOrder(order);
        step.setName(req.name());
        step.setDescription(req.description());
        step.setStepType(req.stepType());
        step.setExecutionMode(req.executionMode());
        step.setAutomationCandidate(Boolean.TRUE.equals(req.automationCandidate()));
        step.setAutomationRationale(req.automationRationale());
        step.setLinkedSystemId(req.linkedSystemId());
        step.setAssignedPersonId(req.assignedPersonId());
        step.setEstimatedMinutes(req.estimatedMinutes());
        step.setCreatedBy("api");
        ProcessStep saved = repository.save(step);
        log.debug("Created process step [id={}, processId={}, order={}]",
                saved.getId(), saved.getProcessId(), saved.getSequenceOrder());
        return saved;
    }

    @Governed(actionType = "process_step.update")
    @Transactional
    public ProcessStep update(UUID id, ProcessStepCreateRequest req) {
        ProcessStep step = get(id);
        if (req.sequenceOrder() != null) step.setSequenceOrder(req.sequenceOrder());
        if (req.name() != null) step.setName(req.name());
        if (req.description() != null) step.setDescription(req.description());
        if (req.stepType() != null) step.setStepType(req.stepType());
        if (req.executionMode() != null) step.setExecutionMode(req.executionMode());
        if (req.automationCandidate() != null) step.setAutomationCandidate(req.automationCandidate());
        if (req.automationRationale() != null) step.setAutomationRationale(req.automationRationale());
        if (req.linkedSystemId() != null) step.setLinkedSystemId(req.linkedSystemId());
        if (req.assignedPersonId() != null) step.setAssignedPersonId(req.assignedPersonId());
        if (req.estimatedMinutes() != null) step.setEstimatedMinutes(req.estimatedMinutes());
        return repository.save(step);
    }

    @Governed(actionType = "process_step.delete")
    @Transactional
    public void delete(UUID id) {
        repository.deleteById(id);
    }
}
