package com.meridian.substrate.app.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.meridian.substrate.common.domain.DesignDecision;
import com.meridian.substrate.common.domain.Finding;
import com.meridian.substrate.common.domain.enums.DecisionStatus;
import com.meridian.substrate.common.domain.enums.DecisionType;
import com.meridian.substrate.common.domain.enums.FindingStatus;
import com.meridian.substrate.common.domain.enums.FindingType;
import com.meridian.substrate.common.domain.enums.Severity;
import com.meridian.substrate.common.repo.FindingRepository;
import com.meridian.substrate.engine.decision.log.DecisionLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Seeds diagnostic output: findings (observations about the domain landscape)
 * and design decisions (responses to those findings). Both cluster in the
 * same bounded context because they're the diagnostic layer — derived from
 * observations about the other entities rather than being primary entities
 * themselves.
 *
 * <p>Design decisions go through {@link DecisionLogService#appendDecision}
 * rather than a direct repository save to preserve the hash chain (each
 * decision references the previous decision's hash).
 *
 * <p>Fixture-seam note (port-map §5 S2): every Finding seeded here is
 * hand-written demo fixture data with {@code aiGenerated=false}. Real
 * production findings come from the engine.diagnostic commit step
 * (ValidativeModeService FindingCandidate → Finding(aiGenerated=true)),
 * never from this seeder.
 */
@Component
public class DiagnosticSeeder implements Seeder {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticSeeder.class);

    private final ObjectMapper objectMapper;
    private final FindingRepository findingRepository;
    private final DecisionLogService decisionLogService;

    public DiagnosticSeeder(ObjectMapper objectMapper,
                             FindingRepository findingRepository,
                             DecisionLogService decisionLogService) {
        this.objectMapper = objectMapper;
        this.findingRepository = findingRepository;
        this.decisionLogService = decisionLogService;
    }

    @Override
    public void seedBulk(SeedContext ctx) {
        UUID domainId = ctx.getDomainId();
        try {
            int findings = seedFindings(domainId, ctx);
            int decisions = seedDecisions(domainId, ctx);
            log.info("[seed] DiagnosticSeeder: {} findings, {} decisions", findings, decisions);
        } catch (IOException e) {
            throw new RuntimeException("DiagnosticSeeder failed", e);
        }
    }

    private int seedFindings(UUID domainId, SeedContext ctx) throws IOException {
        List<JsonNode> nodes = SeedFiles.load(objectMapper, "demopack/findings.json");
        int count = 0;
        for (JsonNode n : nodes) {
            Finding finding = new Finding();
            finding.setDomainId(domainId);
            finding.setTitle(n.get("title").asText());
            finding.setDescription(n.get("description").asText());
            finding.setFindingType(FindingType.valueOf(n.get("findingType").asText()));
            finding.setSeverity(Severity.valueOf(n.get("severity").asText()));
            finding.setStatus(FindingStatus.valueOf(n.get("status").asText()));
            finding.setRecommendation(SeedFiles.textOrNull(n, "recommendation"));
            finding.setAiGenerated(false);

            if (n.has("affectedSystemNames") && n.get("affectedSystemNames").isArray()) {
                ArrayNode arr = objectMapper.createArrayNode();
                for (JsonNode sysName : n.get("affectedSystemNames")) {
                    UUID sysId = ctx.systemsByName().get(sysName.asText());
                    if (sysId != null) {
                        arr.add(sysId.toString());
                    } else {
                        log.warn("System not found for finding '{}': {}", finding.getTitle(), sysName.asText());
                    }
                }
                finding.setAffectedSystemIds(arr);
            }

            if (n.has("affectedProcessNames") && n.get("affectedProcessNames").isArray()) {
                ArrayNode arr = objectMapper.createArrayNode();
                for (JsonNode procName : n.get("affectedProcessNames")) {
                    UUID procId = ctx.processesByName().get(procName.asText());
                    if (procId != null) {
                        arr.add(procId.toString());
                    } else {
                        log.warn("Process not found for finding '{}': {}", finding.getTitle(), procName.asText());
                    }
                }
                finding.setAffectedProcessIds(arr);
            }

            finding.setCreatedBy("seed");
            findingRepository.save(finding);
            count++;
        }
        return count;
    }

    private int seedDecisions(UUID domainId, SeedContext ctx) throws IOException {
        List<JsonNode> nodes = SeedFiles.load(objectMapper, "demopack/design-decisions.json");
        int count = 0;
        for (JsonNode n : nodes) {
            DesignDecision decision = new DesignDecision();
            decision.setDomainId(domainId);
            decision.setTitle(n.get("title").asText());
            decision.setDescription(n.get("description").asText());
            decision.setDecisionType(DecisionType.valueOf(n.get("decisionType").asText()));
            decision.setStatus(DecisionStatus.valueOf(n.get("status").asText()));
            decision.setRationale(SeedFiles.textOrNull(n, "rationale"));

            if (n.has("affectedSystemNames") && n.get("affectedSystemNames").isArray()) {
                ArrayNode arr = objectMapper.createArrayNode();
                for (JsonNode sysName : n.get("affectedSystemNames")) {
                    UUID sysId = ctx.systemsByName().get(sysName.asText());
                    if (sysId != null) arr.add(sysId.toString());
                }
                decision.setAffectedSystemIds(arr);
            }

            if (n.has("affectedProcessNames") && n.get("affectedProcessNames").isArray()) {
                ArrayNode arr = objectMapper.createArrayNode();
                for (JsonNode procName : n.get("affectedProcessNames")) {
                    UUID procId = ctx.processesByName().get(procName.asText());
                    if (procId != null) arr.add(procId.toString());
                }
                decision.setAffectedProcessIds(arr);
            }

            decision.setCreatedBy("seed");
            decisionLogService.appendDecision(decision);
            count++;
        }
        return count;
    }

    @Override
    public List<Class<? extends Seeder>> dependsOn() {
        return List.of(DomainSeeder.class, SystemSeeder.class, ProcessSeeder.class);
    }
}
