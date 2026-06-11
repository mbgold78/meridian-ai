package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.app.core.service.EcosystemService;
import com.meridian.substrate.app.core.service.FindingService;
import com.meridian.substrate.app.core.service.ProcessService;
import com.meridian.substrate.app.core.service.SystemService;
import com.meridian.substrate.common.domain.*;
import com.meridian.substrate.common.domain.Process;
import com.meridian.substrate.common.domain.enums.CurationStatus;
import com.meridian.substrate.common.repo.CanonicalEntityRepository;
import com.meridian.substrate.common.repo.EntityOverlapRepository;
import com.meridian.substrate.common.repo.IntegrationRepository;
import com.meridian.substrate.common.repo.ProcessSystemLinkRepository;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST controller for ecosystem-level views including the graph, findings,
 * entity overlaps, and manual dependencies.
 */
@RestController
@RequestMapping("/api/v1/ecosystem")
public class EcosystemController {

    private final EcosystemService ecosystemService;
    private final FindingService findingService;
    private final EntityOverlapRepository entityOverlapRepository;
    private final ProcessService processService;
    private final ProcessSystemLinkRepository processSystemLinkRepository;
    private final SystemService systemService;
    private final IntegrationRepository integrationRepository;
    private final CanonicalEntityRepository canonicalEntityRepository;

    public EcosystemController(EcosystemService ecosystemService,
                               FindingService findingService,
                               EntityOverlapRepository entityOverlapRepository,
                               ProcessService processService,
                               ProcessSystemLinkRepository processSystemLinkRepository,
                               SystemService systemService,
                               IntegrationRepository integrationRepository,
                               CanonicalEntityRepository canonicalEntityRepository) {
        this.ecosystemService = ecosystemService;
        this.findingService = findingService;
        this.entityOverlapRepository = entityOverlapRepository;
        this.processService = processService;
        this.processSystemLinkRepository = processSystemLinkRepository;
        this.systemService = systemService;
        this.integrationRepository = integrationRepository;
        this.canonicalEntityRepository = canonicalEntityRepository;
    }

    @GetMapping("/graph")
    public EcosystemData getEcosystemGraph(@RequestParam UUID domainId) {
        return new EcosystemData(
                systemService.getSystemsByDomain(domainId),
                integrationRepository.findByDomainId(domainId),
                canonicalEntityRepository.findByDomainId(domainId),
                entityOverlapRepository.findByDomainId(domainId),
                processService.getProcessesByDomain(domainId)
        );
    }

    public record EcosystemData(
            List<SystemEntity> systems,
            List<Integration> integrations,
            List<CanonicalEntity> canonicalEntities,
            List<EntityOverlap> entityOverlaps,
            List<Process> processes
    ) {}

    /**
     * Findings list with the §9 curation filters (engine-spec diagnostic.md
     * §9/§10.1; ADR-004 D10) — additive params, changed DEFAULT:
     * {@code curation_status NOT IN (REJECTED, SUPERSEDED) OR NULL}. NULL =
     * not subject to curation (DiagnosticSeeder fixture rows stay NULL —
     * demo parity pinned, test plan #7); {@code includeRejected=true}
     * restores the excluded rows; an explicit {@code curationStatus=} value
     * overrides the default (asking for REJECTED shows REJECTED).
     *
     * <p>Frontend grep-audit (the spec's named implementation step,
     * endpoints-barrel-omission precedent): the sole consumer is
     * {@code frontend/src/api/endpoints/findings.ts#getFindings} (used by
     * {@code LivingCanvas.tsx}), which passes no curation params — it now
     * gets the default view (REJECTED/SUPERSEDED hidden; fixture rows
     * unchanged), which is the intended product behavior.
     */
    @GetMapping("/findings")
    public List<Finding> getFindingsByDomain(
            @RequestParam UUID domainId,
            @RequestParam(name = "curationStatus", required = false) CurationStatus curationStatus,
            @RequestParam(name = "includeRejected", defaultValue = "false") boolean includeRejected,
            @RequestParam(name = "aiGenerated", required = false) Boolean aiGenerated) {
        return findingService.getFindingsByDomain(domainId, curationStatus, includeRejected, aiGenerated);
    }

    @GetMapping("/overlaps")
    public List<EntityOverlap> getOverlapsByDomain(@RequestParam UUID domainId) {
        return entityOverlapRepository.findByDomainId(domainId);
    }

    /**
     * Retrieve processes that bridge multiple systems within a domain.
     * A "manual dependency" is a process performed manually that acts as a bridge
     * between two or more systems — the pattern the redesign aims to reduce.
     */
    @GetMapping("/manual-dependencies")
    public List<Process> getManualDependencies(@RequestParam UUID domainId) {
        List<Process> allProcesses = processService.getProcessesByDomain(domainId);
        List<Process> bridging = new ArrayList<>();

        for (Process p : allProcesses) {
            List<ProcessSystemLink> links =
                    processSystemLinkRepository.findByProcessId(p.getId());
            Set<UUID> linkedSystems = new HashSet<>();
            for (ProcessSystemLink link : links) {
                linkedSystems.add(link.getSystemId());
            }
            if (linkedSystems.size() >= 2) {
                bridging.add(p);
            }
        }

        return bridging;
    }
}
