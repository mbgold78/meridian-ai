package com.meridian.substrate.app.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.meridian.substrate.common.domain.Finding;
import com.meridian.substrate.common.domain.Process;
import com.meridian.substrate.common.domain.ProcessSystemLink;
import com.meridian.substrate.common.domain.enums.*;
import com.meridian.substrate.common.repo.FindingRepository;
import com.meridian.substrate.common.repo.ProcessRepository;
import com.meridian.substrate.common.repo.ProcessSystemLinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Analyzes manual processes within a domain and generates findings for
 * processes that exhibit risk patterns such as tribal knowledge dependency,
 * undocumented workflows, compensating control risks, shadow workflows,
 * high-volume reconciliations, and multi-system bridge dependencies.
 */
@Service
public class ManualProcessAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ManualProcessAnalyzer.class);

    private final ProcessRepository processRepository;
    private final ProcessSystemLinkRepository linkRepository;
    private final FindingRepository findingRepository;
    private final ObjectMapper mapper;

    public ManualProcessAnalyzer(ProcessRepository processRepository,
                                 ProcessSystemLinkRepository linkRepository,
                                 FindingRepository findingRepository,
                                 ObjectMapper mapper) {
        this.processRepository = processRepository;
        this.linkRepository = linkRepository;
        this.findingRepository = findingRepository;
        this.mapper = mapper;
    }

    /**
     * Scan all manual processes in the given domain and generate findings
     * for detected risk patterns. Persists and returns all new findings.
     *
     * @param domainId the domain to analyze
     * @return list of newly generated findings
     */
    public List<Finding> analyzeManualProcesses(UUID domainId) {
        log.info("Analyzing manual processes for domain {}", domainId);

        List<Process> processes = processRepository.findByDomainId(domainId);
        List<Finding> findings = new ArrayList<>();

        for (Process process : processes) {
            // TRIBAL_KNOWLEDGE_RISK: documentationQuality=TRIBAL_ONLY and riskRating >= HIGH
            if (DocumentationQuality.TRIBAL_ONLY.equals(process.getDocumentationQuality())
                    && isHighOrCritical(process.getRiskRating())) {
                findings.add(buildFinding(domainId, process,
                        FindingType.TRIBAL_KNOWLEDGE_RISK,
                        mapRiskToSeverity(process.getRiskRating()),
                        "Tribal Knowledge Risk: " + process.getName(),
                        String.format("Manual process '%s' (owner: %s) relies entirely on tribal knowledge "
                                        + "with a %s risk rating. Loss of key personnel could disrupt this process.",
                                process.getName(), process.getPrimaryOwner(),
                                process.getRiskRating()),
                        "Document this process formally and establish cross-training to reduce single-point-of-failure risk."));
            }

            // UNDOCUMENTED_WORKFLOW: documentationQuality=UNDOCUMENTED
            if (DocumentationQuality.UNDOCUMENTED.equals(process.getDocumentationQuality())) {
                findings.add(buildFinding(domainId, process,
                        FindingType.UNDOCUMENTED_WORKFLOW,
                        Severity.MEDIUM,
                        "Undocumented Workflow: " + process.getName(),
                        String.format("Manual process '%s' (owner: %s) has no documentation. "
                                        + "This creates audit risk and operational fragility.",
                                process.getName(), process.getPrimaryOwner()),
                        "Create written documentation or standard operating procedures for this workflow."));
            }

            // COMPENSATING_CONTROL_RISK: classification=COMPENSATING_CONTROL and riskRating >= HIGH
            if (ManualProcessClassification.COMPENSATING_CONTROL.equals(process.getClassification())
                    && isHighOrCritical(process.getRiskRating())) {
                findings.add(buildFinding(domainId, process,
                        FindingType.COMPENSATING_CONTROL_RISK,
                        mapRiskToSeverity(process.getRiskRating()),
                        "Compensating Control Risk: " + process.getName(),
                        String.format("Manual process '%s' (owner: %s) acts as a compensating control with a "
                                        + "%s risk rating. Failure of this control could leave the organization exposed.",
                                process.getName(), process.getPrimaryOwner(),
                                process.getRiskRating()),
                        "Evaluate whether this compensating control can be automated or replaced by a system-native control."));
            }

            // SHADOW_WORKFLOW_DETECTED: classification=SHADOW_WORKFLOW
            if (ManualProcessClassification.SHADOW_WORKFLOW.equals(process.getClassification())) {
                findings.add(buildFinding(domainId, process,
                        FindingType.SHADOW_WORKFLOW_DETECTED,
                        Severity.HIGH,
                        "Shadow Workflow Detected: " + process.getName(),
                        String.format("Manual process '%s' (owner: %s) is classified as a shadow workflow, "
                                        + "indicating it operates outside formal IT governance.",
                                process.getName(), process.getPrimaryOwner()),
                        "Bring this workflow under formal governance or integrate its function into an approved system."));
            }

            // MANUAL_RECONCILIATION_VOLUME: classification=RECONCILIATION and estimatedFte > 1.0
            if (ManualProcessClassification.RECONCILIATION.equals(process.getClassification())
                    && process.getEstimatedFte() != null && process.getEstimatedFte() > 1.0) {
                findings.add(buildFinding(domainId, process,
                        FindingType.MANUAL_RECONCILIATION_VOLUME,
                        Severity.MEDIUM,
                        "High-Volume Manual Reconciliation: " + process.getName(),
                        String.format("Manual process '%s' (owner: %s) is a reconciliation process consuming "
                                        + "%.1f FTE. This volume suggests an automation opportunity.",
                                process.getName(), process.getPrimaryOwner(),
                                process.getEstimatedFte()),
                        "Evaluate reconciliation automation tools to reduce manual effort and error risk."));
            }

            // MANUAL_PROCESS_DEPENDENCY: links to 3+ systems (bridge between many systems)
            List<ProcessSystemLink> links = linkRepository.findByProcessId(process.getId());
            if (links.size() >= 3) {
                findings.add(buildFinding(domainId, process,
                        FindingType.MANUAL_PROCESS_DEPENDENCY,
                        Severity.HIGH,
                        "Multi-System Dependency: " + process.getName(),
                        String.format("Manual process '%s' (owner: %s) bridges %d systems, creating a "
                                        + "critical manual dependency in the integration landscape.",
                                process.getName(), process.getPrimaryOwner(), links.size()),
                        "Consider system integration or middleware to reduce reliance on this manual bridge process."));
            }
        }

        // Persist all findings
        if (!findings.isEmpty()) {
            findings = findingRepository.saveAll(findings);
            log.info("Generated {} manual process findings for domain {}", findings.size(), domainId);
        } else {
            log.info("No manual process findings generated for domain {}", domainId);
        }

        return findings;
    }

    private Finding buildFinding(UUID domainId, Process process,
                                 FindingType findingType, Severity severity,
                                 String title, String description, String recommendation) {
        Finding finding = new Finding();
        finding.setDomainId(domainId);
        finding.setFindingType(findingType);
        finding.setSeverity(severity);
        finding.setStatus(FindingStatus.OPEN);
        finding.setTitle(title);
        finding.setDescription(description);
        finding.setRecommendation(recommendation);
        finding.setAiGenerated(true);

        // Set affected manual process IDs
        ArrayNode processIds = mapper.createArrayNode();
        processIds.add(process.getId().toString());
        finding.setAffectedProcessIds(processIds);

        return finding;
    }

    private boolean isHighOrCritical(RiskRating rating) {
        return RiskRating.HIGH.equals(rating) || RiskRating.CRITICAL.equals(rating);
    }

    private Severity mapRiskToSeverity(RiskRating riskRating) {
        if (riskRating == null) return Severity.MEDIUM;
        switch (riskRating) {
            case LOW: return Severity.LOW;
            case MEDIUM: return Severity.MEDIUM;
            case HIGH: return Severity.HIGH;
            case CRITICAL: return Severity.CRITICAL;
            default: return Severity.MEDIUM;
        }
    }
}
