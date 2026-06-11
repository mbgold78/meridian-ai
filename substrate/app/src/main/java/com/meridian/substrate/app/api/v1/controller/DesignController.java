package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.app.core.exception.EntityNotFoundException;
import com.meridian.substrate.common.domain.DesignDecision;
import com.meridian.substrate.common.domain.enums.DecisionStatus;
import com.meridian.substrate.common.repo.DesignDecisionRepository;
import com.meridian.substrate.engine.decision.impact.ImpactAnalysisResult;
import com.meridian.substrate.engine.decision.impact.ImpactAnalysisService;
import com.meridian.substrate.engine.decision.log.DecisionLogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for design decision management including creation,
 * approval/rejection workflow, and impact analysis.
 *
 * <p><b>Transaction-shape note (chunk C4 contract):</b> {@code appendDecision}
 * manages its own transaction inside a per-domain lock — it must be called
 * directly from the controller (no {@code @Transactional} on this layer), or
 * the commit would escape the locked region and re-open the chain-fork window.
 */
@RestController
@RequestMapping("/api/v1/design")
public class DesignController {

    private final DecisionLogService decisionLogService;
    private final DesignDecisionRepository designDecisionRepository;
    private final ImpactAnalysisService impactAnalysisService;

    public DesignController(DecisionLogService decisionLogService,
                            DesignDecisionRepository designDecisionRepository,
                            ImpactAnalysisService impactAnalysisService) {
        this.decisionLogService = decisionLogService;
        this.designDecisionRepository = designDecisionRepository;
        this.impactAnalysisService = impactAnalysisService;
    }

    /**
     * Append a new design decision to the hash-chained decision log.
     *
     * @param decision the decision to create
     * @return the persisted decision with hash fields populated
     */
    @PostMapping("/decisions")
    public DesignDecision createDecision(@RequestBody DesignDecision decision) {
        return decisionLogService.appendDecision(decision);
    }

    /**
     * Retrieve the full decision log for a domain.
     *
     * @param domainId the domain UUID
     * @return list of decisions in chronological order
     */
    @GetMapping("/decisions")
    public List<DesignDecision> getDecisionLog(@RequestParam UUID domainId) {
        return decisionLogService.getDecisionLog(domainId);
    }

    /**
     * Retrieve a single design decision by its identifier.
     *
     * @param id the decision UUID
     * @return the design decision
     */
    @GetMapping("/decisions/{id}")
    public DesignDecision getDecisionById(@PathVariable UUID id) {
        return designDecisionRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("DesignDecision", id));
    }

    /**
     * Approve a design decision by updating its status to APPROVED.
     *
     * <p><b>Latent homepoc behavior preserved deliberately:</b> {@code status} is
     * part of the frozen canonical-JSON hash input (C4 hash-vector pin), so
     * mutating it here makes {@code /decision-log/verify} flag this decision as a
     * broken link — exactly as homepoc behaved. Resolving this requires a product
     * decision (exclude status from the hash — invalidating existing chains — or
     * model approval as a new chained append); out of C5 port scope.
     *
     * @param id the decision UUID
     * @return the updated decision
     */
    @PostMapping("/decisions/{id}/approve")
    public DesignDecision approveDecision(@PathVariable UUID id) {
        DesignDecision decision = designDecisionRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("DesignDecision", id));
        decision.setStatus(DecisionStatus.APPROVED);
        return designDecisionRepository.save(decision);
    }

    /**
     * Reject a design decision by updating its status to REJECTED.
     *
     * <p>Same hash-input caveat as {@link #approveDecision(UUID)}.
     *
     * @param id the decision UUID
     * @return the updated decision
     */
    @PostMapping("/decisions/{id}/reject")
    public DesignDecision rejectDecision(@PathVariable UUID id) {
        DesignDecision decision = designDecisionRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("DesignDecision", id));
        decision.setStatus(DecisionStatus.REJECTED);
        return designDecisionRepository.save(decision);
    }

    /**
     * Perform an AI-driven impact analysis for a proposed system consolidation.
     *
     * @param request the impact analysis request
     * @return structured impact analysis result
     */
    @PostMapping("/impact-analysis")
    public ImpactAnalysisResult analyzeImpact(@RequestBody ImpactAnalysisRequest request) {
        return impactAnalysisService.analyzeImpact(
                request.domainId(), request.systemIds(), request.consolidationDescription());
    }

    /**
     * Request payload for impact analysis.
     *
     * @param domainId                  the domain UUID
     * @param systemIds                 list of system UUIDs involved in the consolidation
     * @param consolidationDescription  description of the proposed consolidation
     */
    public record ImpactAnalysisRequest(
            UUID domainId,
            List<UUID> systemIds,
            String consolidationDescription
    ) {
        public ImpactAnalysisRequest {
            // Null tolerance: Jackson leaves an omitted array null, and
            // ImpactAnalysisService dereferences systemIds on its first line
            // — an omitted field must degrade to "no systems named", never a
            // 500 (the pre-fix frontend sent a wrong-shaped body and every
            // click NPE'd here).
            systemIds = systemIds == null ? List.of() : systemIds;
        }
    }
}
