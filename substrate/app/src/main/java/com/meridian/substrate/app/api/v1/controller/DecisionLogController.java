package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.common.domain.DesignDecision;
import com.meridian.substrate.engine.decision.log.DecisionLogService;
import com.meridian.substrate.engine.decision.log.HashChainVerification;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for accessing the append-only hash-chained decision log
 * and verifying chain integrity.
 */
@RestController
@RequestMapping("/api/v1/decision-log")
public class DecisionLogController {

    private final DecisionLogService decisionLogService;

    public DecisionLogController(DecisionLogService decisionLogService) {
        this.decisionLogService = decisionLogService;
    }

    /**
     * Retrieve the full decision log for a domain in chronological order.
     *
     * @param domainId the domain UUID
     * @return list of decisions in the domain's chain
     */
    @GetMapping
    public List<DesignDecision> getDecisionLog(@RequestParam UUID domainId) {
        return decisionLogService.getDecisionLog(domainId);
    }

    /**
     * Verify the integrity of the hash chain for a domain.
     *
     * @param domainId the domain UUID
     * @return verification result describing chain integrity
     */
    @GetMapping("/verify")
    public HashChainVerification verifyChain(@RequestParam UUID domainId) {
        return decisionLogService.verifyChain(domainId);
    }
}
