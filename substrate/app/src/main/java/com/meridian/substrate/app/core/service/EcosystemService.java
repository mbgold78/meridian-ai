package com.meridian.substrate.app.core.service;

import com.meridian.substrate.common.domain.Integration;
import com.meridian.substrate.common.domain.Process;
import com.meridian.substrate.common.domain.ProcessSystemLink;
import com.meridian.substrate.common.domain.SystemEntity;
import com.meridian.substrate.common.repo.IntegrationRepository;
import com.meridian.substrate.common.repo.ProcessRepository;
import com.meridian.substrate.common.repo.ProcessSystemLinkRepository;
import com.meridian.substrate.common.repo.SystemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Builds an ecosystem graph for a domain, combining system nodes, process nodes,
 * and edges from integrations and process-system links.
 */
@Service
public class EcosystemService {

    private static final Logger log = LoggerFactory.getLogger(EcosystemService.class);

    private final SystemRepository systemRepository;
    private final ProcessRepository processRepository;
    private final IntegrationRepository integrationRepository;
    private final ProcessSystemLinkRepository processSystemLinkRepository;

    public EcosystemService(SystemRepository systemRepository,
                            ProcessRepository processRepository,
                            IntegrationRepository integrationRepository,
                            ProcessSystemLinkRepository processSystemLinkRepository) {
        this.systemRepository = systemRepository;
        this.processRepository = processRepository;
        this.integrationRepository = integrationRepository;
        this.processSystemLinkRepository = processSystemLinkRepository;
    }

    @Transactional(readOnly = true)
    public EcosystemGraph getEcosystemGraph(UUID domainId) {
        log.debug("Building ecosystem graph for domain [domainId={}]", domainId);

        List<EcosystemGraph.GraphNode> nodes = new ArrayList<>();
        List<EcosystemGraph.GraphEdge> edges = new ArrayList<>();

        List<SystemEntity> systems = systemRepository.findByDomainId(domainId);
        for (SystemEntity system : systems) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("vendor", system.getVendor());
            data.put("systemType", system.getSystemType());
            data.put("status", system.getStatus());
            data.put("businessLine", system.getBusinessLine());

            nodes.add(new EcosystemGraph.GraphNode(
                    system.getId().toString(),
                    system.getName(),
                    "system",
                    data
            ));
        }

        List<Process> processes = processRepository.findByDomainId(domainId);
        for (Process p : processes) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("classification", p.getClassification() != null ? p.getClassification().name() : null);
            data.put("frequency", p.getFrequency());
            data.put("primaryOwner", p.getPrimaryOwner());
            data.put("riskRating", p.getRiskRating() != null ? p.getRiskRating().name() : null);

            nodes.add(new EcosystemGraph.GraphNode(
                    p.getId().toString(),
                    p.getName(),
                    "process",
                    data
            ));
        }

        List<Integration> integrations = integrationRepository.findByDomainId(domainId);
        for (Integration integration : integrations) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("name", integration.getName());
            data.put("integrationType", integration.getIntegrationType() != null
                    ? integration.getIntegrationType().name() : null);
            data.put("integrationStatus", integration.getIntegrationStatus() != null
                    ? integration.getIntegrationStatus().name() : null);
            data.put("frequency", integration.getFrequency());

            edges.add(new EcosystemGraph.GraphEdge(
                    integration.getId().toString(),
                    integration.getSourceSystemId().toString(),
                    integration.getTargetSystemId().toString(),
                    "integration",
                    data
            ));
        }

        for (Process p : processes) {
            List<ProcessSystemLink> links =
                    processSystemLinkRepository.findByProcessId(p.getId());
            for (ProcessSystemLink link : links) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("linkType", link.getLinkType() != null ? link.getLinkType().name() : null);
                data.put("frequency", link.getFrequency());
                data.put("status", link.getStatus() != null ? link.getStatus().name() : null);

                edges.add(new EcosystemGraph.GraphEdge(
                        link.getId().toString(),
                        p.getId().toString(),
                        link.getSystemId().toString(),
                        "process_link",
                        data
                ));
            }
        }

        log.info("Built ecosystem graph [domainId={}, nodes={}, edges={}]",
                domainId, nodes.size(), edges.size());

        return new EcosystemGraph(nodes, edges);
    }
}
