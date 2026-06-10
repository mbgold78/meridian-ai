package com.meridian.substrate.app.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.Process;
import com.meridian.substrate.common.domain.ProcessRole;
import com.meridian.substrate.common.domain.ProcessStep;
import com.meridian.substrate.common.domain.ProcessSystemLink;
import com.meridian.substrate.common.domain.enums.DefenseLine;
import com.meridian.substrate.common.domain.enums.DocumentationQuality;
import com.meridian.substrate.common.domain.enums.LinkStatus;
import com.meridian.substrate.common.domain.enums.LinkType;
import com.meridian.substrate.common.domain.enums.ManualProcessClassification;
import com.meridian.substrate.common.domain.enums.Participation;
import com.meridian.substrate.common.domain.enums.ProcessStepExecutionMode;
import com.meridian.substrate.common.domain.enums.RiskRating;
import com.meridian.substrate.common.domain.enums.StepType;
import com.meridian.substrate.common.repo.ProcessRepository;
import com.meridian.substrate.common.repo.ProcessRoleRepository;
import com.meridian.substrate.common.repo.ProcessStepRepository;
import com.meridian.substrate.common.repo.ProcessSystemLinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Seeds the process bounded context: processes, process-system links,
 * process-roles (member assignments), and a Phase 1 sample process step.
 *
 * <p>Phase 1 sample step is idempotent (seed-if-missing) so existing dev DBs
 * pick it up via {@link #seedSamplesForDomain(SeedContext, UUID)} without a wipe.
 */
@Component
public class ProcessSeeder implements Seeder {

    private static final Logger log = LoggerFactory.getLogger(ProcessSeeder.class);

    private final ObjectMapper objectMapper;
    private final ProcessRepository processRepository;
    private final ProcessSystemLinkRepository processSystemLinkRepository;
    private final ProcessRoleRepository processRoleRepository;
    private final ProcessStepRepository processStepRepository;

    public ProcessSeeder(ObjectMapper objectMapper,
                         ProcessRepository processRepository,
                         ProcessSystemLinkRepository processSystemLinkRepository,
                         ProcessRoleRepository processRoleRepository,
                         ProcessStepRepository processStepRepository) {
        this.objectMapper = objectMapper;
        this.processRepository = processRepository;
        this.processSystemLinkRepository = processSystemLinkRepository;
        this.processRoleRepository = processRoleRepository;
        this.processStepRepository = processStepRepository;
    }

    @Override
    public void seedBulk(SeedContext ctx) {
        UUID domainId = ctx.getDomainId();
        try {
            int processes = seedProcesses(domainId, ctx);
            int systemLinks = seedSystemLinks(ctx);
            int roles = seedRoles(ctx);
            log.info("[seed] ProcessSeeder: {} processes, {} system links, {} roles",
                    processes, systemLinks, roles);
            seedSamplesForDomain(ctx, domainId);
        } catch (IOException e) {
            throw new RuntimeException("ProcessSeeder failed", e);
        }
    }

    @Override
    public void seedSamplesForDomain(SeedContext ctx, UUID domainId) {
        UUID sampleProcessId = ctx.processesByName().values().stream().findFirst().orElse(null);
        UUID sampleSystemId = ctx.systemsByName().values().stream().findFirst().orElse(null);
        if (sampleProcessId != null) {
            seedStepIfMissing(sampleProcessId, "Intake review", sampleSystemId);
        }
    }

    private int seedProcesses(UUID domainId, SeedContext ctx) throws IOException {
        List<JsonNode> nodes = SeedFiles.load(objectMapper, "demopack/processes.json");
        for (JsonNode n : nodes) {
            Process p = new Process();
            p.setDomainId(domainId);
            p.setName(n.get("name").asText());
            p.setDescription(n.get("description").asText());
            p.setClassification(ManualProcessClassification.valueOf(n.get("classification").asText()));
            p.setFrequency(SeedFiles.textOrNull(n, "frequency"));
            p.setPrimaryOwner(SeedFiles.textOrNull(n, "primaryOwner"));
            p.setBusinessLine(SeedFiles.textOrNull(n, "businessLine"));
            if (n.has("defenseLine") && !n.get("defenseLine").isNull()) {
                p.setDefenseLine(DefenseLine.valueOf(n.get("defenseLine").asText()));
            }
            if (n.has("documentationQuality") && !n.get("documentationQuality").isNull()) {
                p.setDocumentationQuality(DocumentationQuality.valueOf(n.get("documentationQuality").asText()));
            }
            if (n.has("estimatedFte") && !n.get("estimatedFte").isNull()) {
                p.setEstimatedFte(n.get("estimatedFte").asDouble());
            }
            if (n.has("riskRating") && !n.get("riskRating").isNull()) {
                p.setRiskRating(RiskRating.valueOf(n.get("riskRating").asText()));
            }
            p.setCreatedBy("seed");
            Process saved = processRepository.save(p);
            ctx.processesByName().put(saved.getName(), saved.getId());
        }
        return ctx.processesByName().size();
    }

    private int seedSystemLinks(SeedContext ctx) throws IOException {
        List<JsonNode> nodes = SeedFiles.load(objectMapper, "demopack/process-system-links.json");
        int count = 0;
        for (JsonNode n : nodes) {
            UUID processId = ctx.processesByName().get(n.get("processName").asText());
            UUID systemId = ctx.systemsByName().get(n.get("systemName").asText());
            if (processId == null) {
                log.warn("Process not found for system link: {}", n.get("processName").asText());
                continue;
            }
            if (systemId == null) {
                log.warn("System not found for process link: {}", n.get("systemName").asText());
                continue;
            }
            ProcessSystemLink link = new ProcessSystemLink();
            link.setProcessId(processId);
            link.setSystemId(systemId);
            link.setLinkType(LinkType.valueOf(n.get("linkType").asText()));
            link.setFrequency(SeedFiles.textOrNull(n, "frequency"));
            if (n.has("status") && !n.get("status").isNull()) {
                link.setStatus(LinkStatus.valueOf(n.get("status").asText()));
            }
            link.setAiInferred(false);
            link.setCreatedBy("seed");
            processSystemLinkRepository.save(link);
            count++;
        }
        return count;
    }

    private int seedRoles(SeedContext ctx) throws IOException {
        List<JsonNode> nodes = SeedFiles.load(objectMapper, "demopack/process-roles.json");
        int count = 0;
        for (JsonNode n : nodes) {
            UUID processId = ctx.processesByName().get(n.get("processName").asText());
            UUID memberId = ctx.membersByName().get(n.get("memberName").asText());
            if (processId == null) {
                log.warn("Process not found for role: {}", n.get("processName").asText());
                continue;
            }
            if (memberId == null) {
                log.warn("Member not found for role: {}", n.get("memberName").asText());
                continue;
            }
            ProcessRole role = new ProcessRole();
            role.setProcessId(processId);
            role.setMemberId(memberId);
            role.setParticipation(Participation.valueOf(n.get("participation").asText()));
            role.setTimeCommitment(SeedFiles.textOrNull(n, "timeCommitment"));
            if (n.has("automationCandidate") && !n.get("automationCandidate").isNull()) {
                role.setAutomationCandidate(n.get("automationCandidate").asBoolean());
            }
            role.setAutomationRationale(SeedFiles.textOrNull(n, "automationRationale"));
            role.setCreatedBy("seed");
            processRoleRepository.save(role);
            count++;
        }
        return count;
    }

    private void seedStepIfMissing(UUID processId, String stepName, UUID linkedSystemId) {
        for (ProcessStep existing : processStepRepository.findByProcessIdOrderBySequenceOrderAsc(processId)) {
            if (stepName.equals(existing.getName())) return;
        }
        ProcessStep step = new ProcessStep();
        step.setProcessId(processId);
        step.setSequenceOrder(0);
        step.setName(stepName);
        step.setDescription("Seed example — Phase 3 will author real steps per process.");
        step.setStepType(StepType.HUMAN);
        step.setExecutionMode(ProcessStepExecutionMode.MANUAL);
        step.setAutomationCandidate(true);
        step.setAutomationRationale("Rule-based checklist — straightforward to automate.");
        step.setLinkedSystemId(linkedSystemId);
        step.setEstimatedMinutes(15);
        step.setCreatedBy("seed");
        processStepRepository.save(step);
        log.info("[seed] ProcessSeeder: process_step [processId={}, name='{}']", processId, stepName);
    }

    @Override
    public List<Class<? extends Seeder>> dependsOn() {
        return List.of(DomainSeeder.class, SystemSeeder.class, OrganizationSeeder.class);
    }
}
