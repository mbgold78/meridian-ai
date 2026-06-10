package com.meridian.substrate.engine.decision.impact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.ai.CompletionRequest;
import com.meridian.substrate.common.ai.CompletionResponse;
import com.meridian.substrate.common.ai.LlmClient;
import com.meridian.substrate.common.ai.TokenUsage;
import com.meridian.substrate.common.ai.config.LlmProperties;
import com.meridian.substrate.common.domain.Process;
import com.meridian.substrate.common.domain.ProcessSystemLink;
import com.meridian.substrate.common.domain.SystemEntity;
import com.meridian.substrate.common.domain.enums.LinkType;
import com.meridian.substrate.common.domain.enums.ManualProcessClassification;
import com.meridian.substrate.common.repo.ProcessRepository;
import com.meridian.substrate.common.repo.ProcessSystemLinkRepository;
import com.meridian.substrate.common.repo.SystemRepository;
import com.meridian.substrate.engine.ai.cost.CostTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the ported {@link ImpactAnalysisService}: prompt assembly from
 * repository context, fenced-JSON parsing, and the raw-content fallback.
 */
class ImpactAnalysisServiceTest {

    private static final String MODEL = "test-model";

    private final UUID domainId = UUID.randomUUID();
    private final UUID systemId = UUID.randomUUID();
    private final UUID otherSystemId = UUID.randomUUID();

    private LlmClient llmClient;
    private SystemRepository systemRepository;
    private ProcessRepository processRepository;
    private ProcessSystemLinkRepository linkRepository;
    private CostTracker costTracker;
    private ImpactAnalysisService service;

    private Process linkedProcess;
    private Process unlinkedProcess;

    @BeforeEach
    void setUp() {
        llmClient = mock(LlmClient.class);
        systemRepository = mock(SystemRepository.class);
        processRepository = mock(ProcessRepository.class);
        linkRepository = mock(ProcessSystemLinkRepository.class);

        LlmProperties properties = new LlmProperties();
        properties.getAnthropic().setModel(MODEL);
        properties.getAnthropic().setMaxTokens(2048);

        SystemEntity system = new SystemEntity();
        system.setId(systemId);
        system.setDomainId(domainId);
        system.setName("Archer GRC");
        system.setVendor("RSA");
        system.setSystemType("GRC");
        system.setDescription("Enterprise GRC platform");
        when(systemRepository.findById(systemId)).thenReturn(Optional.of(system));
        when(systemRepository.findById(otherSystemId)).thenReturn(Optional.empty());

        linkedProcess = new Process();
        linkedProcess.setId(UUID.randomUUID());
        linkedProcess.setDomainId(domainId);
        linkedProcess.setName("Monthly Reconciliation");
        linkedProcess.setDescription("Reconciles Archer to the issues portal");
        linkedProcess.setClassification(ManualProcessClassification.RECONCILIATION);

        unlinkedProcess = new Process();
        unlinkedProcess.setId(UUID.randomUUID());
        unlinkedProcess.setDomainId(domainId);
        unlinkedProcess.setName("Unrelated Process");
        unlinkedProcess.setDescription("Touches no target system");
        unlinkedProcess.setClassification(ManualProcessClassification.SIGN_OFF);

        when(processRepository.findByDomainId(domainId))
                .thenReturn(List.of(linkedProcess, unlinkedProcess));

        ProcessSystemLink link = new ProcessSystemLink();
        link.setProcessId(linkedProcess.getId());
        link.setSystemId(systemId);
        link.setLinkType(LinkType.READS_FROM);
        when(linkRepository.findByProcessId(linkedProcess.getId())).thenReturn(List.of(link));
        when(linkRepository.findByProcessId(unlinkedProcess.getId())).thenReturn(List.of());

        costTracker = new CostTracker(properties);
        service = new ImpactAnalysisService(llmClient, properties, systemRepository,
                processRepository, linkRepository, new ObjectMapper(), costTracker);
    }

    private CompletionResponse response(String content) {
        return new CompletionResponse("msg-1", content, MODEL, "end_turn",
                new TokenUsage(100, 50));
    }

    @Test
    void analyzeImpact_parsesFencedJsonResponse() {
        String fenced = """
                ```json
                {
                  "summary": "Consolidation is feasible.",
                  "systemImpacts": [{"systemId": "s1", "systemName": "Archer GRC", "impactType": "MIGRATION", "rationale": "Data moves"}],
                  "processImpacts": [{"processId": "p1", "processName": "Monthly Reconciliation", "impactType": "ELIMINATED", "rationale": "No longer needed", "mitigation": "Archive records"}],
                  "recommendation": "Proceed in phases."
                }
                ```""";
        when(llmClient.complete(any(CompletionRequest.class))).thenReturn(response(fenced));

        ImpactAnalysisResult result = service.analyzeImpact(domainId,
                List.of(systemId, otherSystemId), "Consolidate Archer into IssuesPortal");

        assertThat(result.summary()).isEqualTo("Consolidation is feasible.");
        assertThat(result.systemImpacts()).hasSize(1);
        assertThat(result.systemImpacts().get(0).impactType()).isEqualTo("MIGRATION");
        assertThat(result.processImpacts()).hasSize(1);
        assertThat(result.processImpacts().get(0).mitigation()).isEqualTo("Archive records");
        assertThat(result.recommendation()).isEqualTo("Proceed in phases.");
    }

    @Test
    void analyzeImpact_parsesBareJsonResponse() {
        String bare = "{\"summary\":\"ok\",\"systemImpacts\":[],\"processImpacts\":[],\"recommendation\":\"go\"}";
        when(llmClient.complete(any(CompletionRequest.class))).thenReturn(response(bare));

        ImpactAnalysisResult result = service.analyzeImpact(domainId,
                List.of(systemId), "Consolidate");

        assertThat(result.summary()).isEqualTo("ok");
        assertThat(result.recommendation()).isEqualTo("go");
    }

    @Test
    void analyzeImpact_recordsTokenUsageWithCostTracker() {
        // Cost wiring (port-map S12, added in chunk C5): each impact-analysis
        // completion records its usage so /llm/cost-summary is non-zero.
        when(llmClient.complete(any(CompletionRequest.class)))
                .thenReturn(response("{\"summary\":\"ok\",\"systemImpacts\":[],\"processImpacts\":[],\"recommendation\":\"go\"}"));

        service.analyzeImpact(domainId, List.of(systemId), "Consolidate");

        assertThat(costTracker.getSummary().totalCalls()).isEqualTo(1);
        assertThat(costTracker.getSummary().totalInputTokens()).isEqualTo(100);
        assertThat(costTracker.getSummary().totalOutputTokens()).isEqualTo(50);
    }

    @Test
    void analyzeImpact_unparseableResponse_fallsBackToRawSummary() {
        String prose = "I think this consolidation is broadly sensible because...";
        when(llmClient.complete(any(CompletionRequest.class))).thenReturn(response(prose));

        ImpactAnalysisResult result = service.analyzeImpact(domainId,
                List.of(systemId), "Consolidate");

        assertThat(result.summary()).isEqualTo(prose);
        assertThat(result.systemImpacts()).isEmpty();
        assertThat(result.processImpacts()).isEmpty();
        assertThat(result.recommendation()).contains("Unable to parse structured response");
    }

    @Test
    void analyzeImpact_buildsPromptFromRepositoryContext() {
        when(llmClient.complete(any(CompletionRequest.class)))
                .thenReturn(response("{\"summary\":\"s\",\"systemImpacts\":[],\"processImpacts\":[],\"recommendation\":\"r\"}"));

        service.analyzeImpact(domainId, List.of(systemId), "Merge the GRC stack");

        ArgumentCaptor<CompletionRequest> captor = ArgumentCaptor.forClass(CompletionRequest.class);
        verify(llmClient).complete(captor.capture());
        CompletionRequest request = captor.getValue();

        assertThat(request.model()).isEqualTo(MODEL);
        assertThat(request.maxTokens()).isEqualTo(2048);
        assertThat(request.systemPrompt()).contains("impact analyst");
        assertThat(request.messages()).hasSize(1);

        String prompt = request.messages().get(0).content();
        assertThat(prompt).contains("Merge the GRC stack");
        assertThat(prompt).contains("Archer GRC");
        // Only processes linked to a target system are included
        assertThat(prompt).contains("Monthly Reconciliation");
        assertThat(prompt).doesNotContain("Unrelated Process");
    }
}
