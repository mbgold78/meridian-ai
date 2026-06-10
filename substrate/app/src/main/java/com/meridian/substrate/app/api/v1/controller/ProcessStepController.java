package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.app.api.v1.request.ProcessStepCreateRequest;
import com.meridian.substrate.app.core.service.ProcessStepService;
import com.meridian.substrate.common.domain.ProcessStep;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Process steps are nested under a parent process.
 */
@RestController
@RequestMapping("/api/v1/processes/{processId}/steps")
public class ProcessStepController {

    private final ProcessStepService processStepService;

    public ProcessStepController(ProcessStepService processStepService) {
        this.processStepService = processStepService;
    }

    @GetMapping
    public List<ProcessStep> list(@PathVariable UUID processId) {
        return processStepService.listByProcess(processId);
    }

    @GetMapping("/{stepId}")
    public ProcessStep get(@PathVariable UUID processId, @PathVariable UUID stepId) {
        return processStepService.get(stepId);
    }

    @PostMapping
    public ResponseEntity<ProcessStep> create(@PathVariable UUID processId,
                                              @Valid @RequestBody ProcessStepCreateRequest req) {
        ProcessStep saved = processStepService.create(processId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PatchMapping("/{stepId}")
    public ProcessStep update(@PathVariable UUID processId,
                              @PathVariable UUID stepId,
                              @RequestBody ProcessStepCreateRequest req) {
        return processStepService.update(stepId, req);
    }

    @DeleteMapping("/{stepId}")
    public ResponseEntity<Void> delete(@PathVariable UUID processId, @PathVariable UUID stepId) {
        processStepService.delete(stepId);
        return ResponseEntity.noContent().build();
    }
}
