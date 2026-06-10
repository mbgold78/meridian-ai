package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.app.core.service.ProcessService;
import com.meridian.substrate.common.domain.Process;
import com.meridian.substrate.common.domain.ProcessRole;
import com.meridian.substrate.common.domain.ProcessSystemLink;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for process read operations.
 */
@RestController
@RequestMapping("/api/v1/processes")
public class ProcessController {

    private final ProcessService processService;

    public ProcessController(ProcessService processService) {
        this.processService = processService;
    }

    @GetMapping
    public List<Process> getProcessesByDomain(@RequestParam UUID domainId) {
        return processService.getProcessesByDomain(domainId);
    }

    @GetMapping("/{id}")
    public Process getProcessById(@PathVariable UUID id) {
        return processService.getProcessById(id);
    }

    @GetMapping("/{id}/system-links")
    public List<ProcessSystemLink> getSystemLinksByProcess(@PathVariable UUID id) {
        return processService.getSystemLinksByProcess(id);
    }

    @GetMapping("/{id}/roles")
    public List<ProcessRole> getRolesByProcess(@PathVariable UUID id) {
        return processService.getRolesByProcess(id);
    }
}
