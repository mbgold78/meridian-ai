package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.engine.ai.cost.CostSummary;
import com.meridian.substrate.engine.ai.cost.CostTracker;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing LLM cost tracking data.
 *
 * <p><b>Port note (S12):</b> in homepoc this endpoint always returned zeros
 * because {@code CostTracker.recordUsage} had no callers. In substrate every
 * engine LLM call site records usage (orchestrator iterations, assistive
 * single-shot, eval harness, impact analysis, streaming chat completion), so
 * the summary reflects real process-lifetime totals.
 */
@RestController
@RequestMapping("/api/v1/llm")
public class CostController {

    private final CostTracker costTracker;

    public CostController(CostTracker costTracker) {
        this.costTracker = costTracker;
    }

    /**
     * Retrieve a summary of cumulative LLM usage costs.
     *
     * @return the current cost summary including token counts and estimated costs
     */
    @GetMapping("/cost-summary")
    public CostSummary getCostSummary() {
        return costTracker.getSummary();
    }
}
