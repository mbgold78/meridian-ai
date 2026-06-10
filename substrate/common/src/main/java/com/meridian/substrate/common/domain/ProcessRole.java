package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.meridian.substrate.common.domain.enums.Participation;
import jakarta.persistence.*;

import java.util.UUID;

/**
 * Associates an organization member with a process, defining their participation
 * level, time commitment, and whether the role is a candidate for automation.
 */
@Entity
@Table(name = "process_roles")
public class ProcessRole extends BaseEntity {

    @Column(name = "process_id", nullable = false)
    private UUID processId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "process_id", insertable = false, updatable = false)
    private Process process;

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", insertable = false, updatable = false)
    private OrganizationMember member;

    @Enumerated(EnumType.STRING)
    @Column(name = "participation", nullable = false, length = 50)
    private Participation participation;

    @Column(name = "time_commitment")
    private String timeCommitment;

    @Column(name = "automation_candidate")
    private Boolean automationCandidate;

    @Column(name = "automation_rationale", columnDefinition = "TEXT")
    private String automationRationale;

    public UUID getProcessId() {
        return processId;
    }

    public void setProcessId(UUID processId) {
        this.processId = processId;
    }

    public Process getProcess() {
        return process;
    }

    public void setProcess(Process process) {
        this.process = process;
    }

    public UUID getMemberId() {
        return memberId;
    }

    public void setMemberId(UUID memberId) {
        this.memberId = memberId;
    }

    public OrganizationMember getMember() {
        return member;
    }

    public void setMember(OrganizationMember member) {
        this.member = member;
    }

    public Participation getParticipation() {
        return participation;
    }

    public void setParticipation(Participation participation) {
        this.participation = participation;
    }

    public String getTimeCommitment() {
        return timeCommitment;
    }

    public void setTimeCommitment(String timeCommitment) {
        this.timeCommitment = timeCommitment;
    }

    public Boolean getAutomationCandidate() {
        return automationCandidate;
    }

    public void setAutomationCandidate(Boolean automationCandidate) {
        this.automationCandidate = automationCandidate;
    }

    public String getAutomationRationale() {
        return automationRationale;
    }

    public void setAutomationRationale(String automationRationale) {
        this.automationRationale = automationRationale;
    }
}
