package com.meridian.substrate.engine.decision.impact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.ai.CompletionRequest;
import com.meridian.substrate.common.ai.CompletionResponse;
import com.meridian.substrate.common.ai.LlmClient;
import com.meridian.substrate.common.ai.config.LlmProperties;
import com.meridian.substrate.common.domain.Process;
import com.meridian.substrate.common.domain.ProcessSystemLink;
import com.meridian.substrate.common.domain.SystemEntity;
import com.meridian.substrate.common.repo.ProcessRepository;
import com.meridian.substrate.common.repo.ProcessSystemLinkRepository;
import com.meridian.substrate.common.repo.SystemRepository;
import com.meridian.substrate.engine.ai.cost.CostTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * AI-driven impact analysis on system consolidation proposals.
 * <p>
 * Calls the LLM with the designer-impact prompt and structured context about the
 * target systems and their processes, then parses the structured response.
 *
 * <p><b>Cost wiring (port-map §2.3 / S12, added in chunk C5):</b> token usage from
 * the completion is recorded with {@link CostTracker} so {@code /llm/cost-summary}
 * reflects impact-analysis calls — homepoc never recorded usage at this call site.
 */
@Service
public class ImpactAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ImpactAnalysisService.class);

    private final LlmClient llmClient;
    private final LlmProperties llmProperties;
    private final SystemRepository systemRepository;
    private final ProcessRepository processRepository;
    private final ProcessSystemLinkRepository processSystemLinkRepository;
    private final ObjectMapper objectMapper;
    private final CostTracker costTracker;

    public ImpactAnalysisService(LlmClient llmClient,
                                 LlmProperties llmProperties,
                                 SystemRepository systemRepository,
                                 ProcessRepository processRepository,
                                 ProcessSystemLinkRepository processSystemLinkRepository,
                                 ObjectMapper objectMapper,
                                 CostTracker costTracker) {
        this.llmClient = llmClient;
        this.llmProperties = llmProperties;
        this.systemRepository = systemRepository;
        this.processRepository = processRepository;
        this.processSystemLinkRepository = processSystemLinkRepository;
        this.objectMapper = objectMapper;
        this.costTracker = costTracker;
    }

    public ImpactAnalysisResult analyzeImpact(UUID domainId, List<UUID> systemIds,
                                               String consolidationDescription) {
        log.info("Starting impact analysis [domainId={}, systems={}]", domainId, systemIds.size());

        List<SystemEntity> systems = systemIds.stream()
                .map(id -> systemRepository.findById(id).orElse(null))
                .filter(Objects::nonNull)
                .toList();

        List<Process> allProcesses = processRepository.findByDomainId(domainId);
        Map<UUID, List<ProcessSystemLink>> linksByProcess = new LinkedHashMap<>();
        for (Process p : allProcesses) {
            List<ProcessSystemLink> links =
                    processSystemLinkRepository.findByProcessId(p.getId());
            boolean relevant = links.stream()
                    .anyMatch(link -> systemIds.contains(link.getSystemId()));
            if (relevant) {
                linksByProcess.put(p.getId(), links);
            }
        }

        List<Process> relevantProcesses = allProcesses.stream()
                .filter(p -> linksByProcess.containsKey(p.getId()))
                .toList();

        String prompt = buildImpactPrompt(systems, relevantProcesses, linksByProcess,
                consolidationDescription);

        String model = llmProperties.getAnthropic().getModel();
        int maxTokens = llmProperties.getAnthropic().getMaxTokens();

        List<CompletionRequest.Message> messages = new ArrayList<>();
        messages.add(new CompletionRequest.Message("user", prompt));

        CompletionRequest request = CompletionRequest.of(model,
                "You are an enterprise architecture impact analyst. Respond with valid JSON only.",
                messages, maxTokens);

        CompletionResponse response = llmClient.complete(request);
        if (response.usage() != null) {
            costTracker.recordUsage(
                    response.model() != null ? response.model() : model,
                    response.usage().inputTokens(),
                    response.usage().outputTokens());
        }
        String content = response.content();

        log.debug("Impact analysis LLM response received [length={}]", content.length());

        return parseImpactResult(content);
    }

    private String buildImpactPrompt(List<SystemEntity> systems,
                                     List<Process> processes,
                                     Map<UUID, List<ProcessSystemLink>> linksByProcess,
                                     String consolidationDescription) {
        StringBuilder sb = new StringBuilder();
        sb.append("Analyze the impact of the following system consolidation proposal.\n\n");
        sb.append("## Consolidation Description\n");
        sb.append(consolidationDescription).append("\n\n");

        sb.append("## Affected Systems\n");
        for (SystemEntity system : systems) {
            sb.append(String.format("- **%s** (ID: %s): %s, Vendor: %s, Type: %s\n",
                    system.getName(), system.getId(), system.getDescription(),
                    system.getVendor(), system.getSystemType()));
        }

        sb.append("\n## Affected Processes\n");
        for (Process p : processes) {
            sb.append(String.format("- **%s** (ID: %s): %s, Classification: %s, Risk: %s\n",
                    p.getName(), p.getId(), p.getDescription(),
                    p.getClassification(), p.getRiskRating()));
            List<ProcessSystemLink> links = linksByProcess.get(p.getId());
            if (links != null && !links.isEmpty()) {
                sb.append("  System links: ");
                for (ProcessSystemLink link : links) {
                    sb.append(String.format("[%s -> %s (%s)] ",
                            link.getProcessId(), link.getSystemId(), link.getLinkType()));
                }
                sb.append("\n");
            }
        }

        sb.append("\n## Instructions\n");
        sb.append("Respond with a JSON object matching this structure:\n");
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"summary\": \"...\",\n");
        sb.append("  \"systemImpacts\": [{\"systemId\": \"...\", \"systemName\": \"...\", \"impactType\": \"...\", \"rationale\": \"...\"}],\n");
        sb.append("  \"processImpacts\": [{\"processId\": \"...\", \"processName\": \"...\", \"impactType\": \"...\", \"rationale\": \"...\", \"mitigation\": \"...\"}],\n");
        sb.append("  \"recommendation\": \"...\"\n");
        sb.append("}\n");
        sb.append("```\n");

        return sb.toString();
    }

    private ImpactAnalysisResult parseImpactResult(String content) {
        try {
            String json = content.trim();
            if (json.startsWith("```")) {
                int start = json.indexOf('\n') + 1;
                int end = json.lastIndexOf("```");
                if (start > 0 && end > start) {
                    json = json.substring(start, end).trim();
                }
            }

            return objectMapper.readValue(json, ImpactAnalysisResult.class);
        } catch (Exception e) {
            log.warn("Failed to parse LLM response as JSON, wrapping as summary: {}", e.getMessage());
            return new ImpactAnalysisResult(
                    content,
                    List.of(),
                    List.of(),
                    "Unable to parse structured response. See summary for raw analysis."
            );
        }
    }
}
