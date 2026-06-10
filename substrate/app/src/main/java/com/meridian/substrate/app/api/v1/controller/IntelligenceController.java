package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.app.core.service.AssistantService;
import com.meridian.substrate.app.core.service.ReductionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Intelligence endpoints: the reduction thesis (what should we keep/retire/save)
 * and the assistant status (what's missing, what's blocking, what to do next).
 *
 * Both are stateless — computed from the current graph on each request.
 */
@RestController
@RequestMapping("/api/v1/intelligence")
public class IntelligenceController {

    private final ReductionService reductionService;
    private final AssistantService assistantService;

    public IntelligenceController(ReductionService reductionService,
                                  AssistantService assistantService) {
        this.reductionService = reductionService;
        this.assistantService = assistantService;
    }

    @GetMapping("/thesis")
    public ReductionService.Thesis getThesis(
            @RequestParam UUID domainId,
            @RequestParam(required = false, defaultValue = "AGGRESSIVE") ReductionService.Mode mode) {
        return reductionService.reduce(domainId, mode);
    }

    @GetMapping("/assistant")
    public AssistantService.AssistantStatus getAssistantStatus(@RequestParam UUID domainId) {
        return assistantService.evaluate(domainId);
    }
}
