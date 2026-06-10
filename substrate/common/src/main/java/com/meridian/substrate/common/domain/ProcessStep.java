package com.meridian.substrate.common.domain;

import com.meridian.substrate.common.domain.enums.ProcessStepExecutionMode;
import com.meridian.substrate.common.domain.enums.StepType;
import jakarta.persistence.*;

import java.util.UUID;

/**
 * A single step within a Process. ProcessStep carries agent-readiness at the
 * step level and is the primary schema gap the redesign closes (doc 05).
 */
@Entity
@Table(name = "process_steps")
public class ProcessStep extends BaseEntity {

    @Column(name = "process_id", nullable = false)
    private UUID processId;

    @Column(name = "sequence_order", nullable = false)
    private int sequenceOrder;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "step_type", length = 50)
    private StepType stepType;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_mode", length = 50)
    private ProcessStepExecutionMode executionMode;

    @Column(name = "automation_candidate", nullable = false)
    private boolean automationCandidate;

    @Column(name = "automation_rationale", columnDefinition = "TEXT")
    private String automationRationale;

    @Column(name = "agent_readiness_score")
    private Integer agentReadinessScore;

    @Column(name = "linked_system_id")
    private UUID linkedSystemId;

    @Column(name = "assigned_person_id")
    private UUID assignedPersonId;

    @Column(name = "estimated_minutes")
    private Integer estimatedMinutes;

    public UUID getProcessId() { return processId; }
    public void setProcessId(UUID processId) { this.processId = processId; }

    public int getSequenceOrder() { return sequenceOrder; }
    public void setSequenceOrder(int sequenceOrder) { this.sequenceOrder = sequenceOrder; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public StepType getStepType() { return stepType; }
    public void setStepType(StepType stepType) { this.stepType = stepType; }

    public ProcessStepExecutionMode getExecutionMode() { return executionMode; }
    public void setExecutionMode(ProcessStepExecutionMode executionMode) { this.executionMode = executionMode; }

    public boolean isAutomationCandidate() { return automationCandidate; }
    public void setAutomationCandidate(boolean automationCandidate) { this.automationCandidate = automationCandidate; }

    public String getAutomationRationale() { return automationRationale; }
    public void setAutomationRationale(String automationRationale) { this.automationRationale = automationRationale; }

    public Integer getAgentReadinessScore() { return agentReadinessScore; }
    public void setAgentReadinessScore(Integer agentReadinessScore) { this.agentReadinessScore = agentReadinessScore; }

    public UUID getLinkedSystemId() { return linkedSystemId; }
    public void setLinkedSystemId(UUID linkedSystemId) { this.linkedSystemId = linkedSystemId; }

    public UUID getAssignedPersonId() { return assignedPersonId; }
    public void setAssignedPersonId(UUID assignedPersonId) { this.assignedPersonId = assignedPersonId; }

    public Integer getEstimatedMinutes() { return estimatedMinutes; }
    public void setEstimatedMinutes(Integer estimatedMinutes) { this.estimatedMinutes = estimatedMinutes; }
}
