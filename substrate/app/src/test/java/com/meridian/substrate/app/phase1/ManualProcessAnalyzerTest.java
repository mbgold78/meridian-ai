package com.meridian.substrate.app.phase1;

import com.meridian.substrate.app.core.service.ManualProcessAnalyzer;
import com.meridian.substrate.common.domain.Finding;
import com.meridian.substrate.common.domain.Process;
import com.meridian.substrate.common.domain.enums.DocumentationQuality;
import com.meridian.substrate.common.domain.enums.FindingType;
import com.meridian.substrate.common.domain.enums.ManualProcessClassification;
import com.meridian.substrate.common.domain.enums.RiskRating;
import com.meridian.substrate.common.domain.enums.Severity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ManualProcessAnalyzer has no REST endpoint of its own (it backs diagnostic
 * flows), so the analyzer rules are pinned here at the service level: each risk
 * pattern yields a persisted OPEN finding with aiGenerated=true and the
 * affected process id recorded.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Transactional
class ManualProcessAnalyzerTest extends Phase1StageASupport {

    @Autowired ManualProcessAnalyzer analyzer;

    @Test
    void generatesFindingsForRiskPatterns() {
        UUID domainId = createDomain();

        // Pattern 1: tribal knowledge + HIGH risk → TRIBAL_KNOWLEDGE_RISK (severity HIGH)
        Process tribal = new Process();
        tribal.setDomainId(domainId);
        tribal.setName("Quarter-end override ledger");
        tribal.setDescription("Single-owner workaround");
        tribal.setClassification(ManualProcessClassification.TRIBAL_KNOWLEDGE);
        tribal.setDocumentationQuality(DocumentationQuality.TRIBAL_ONLY);
        tribal.setRiskRating(RiskRating.HIGH);
        tribal.setPrimaryOwner("J. Moreno");
        tribal.setCreatedBy("test");
        UUID tribalId = processRepository.save(tribal).getId();

        // Pattern 2: undocumented → UNDOCUMENTED_WORKFLOW (severity MEDIUM)
        Process undocumented = new Process();
        undocumented.setDomainId(domainId);
        undocumented.setName("Ad-hoc exception report");
        undocumented.setDescription("No SOP exists");
        undocumented.setClassification(ManualProcessClassification.OTHER);
        undocumented.setDocumentationQuality(DocumentationQuality.UNDOCUMENTED);
        undocumented.setCreatedBy("test");
        processRepository.save(undocumented);

        List<Finding> findings = analyzer.analyzeManualProcesses(domainId);

        assertThat(findings).hasSize(2);

        Finding tribalFinding = findings.stream()
                .filter(f -> f.getFindingType() == FindingType.TRIBAL_KNOWLEDGE_RISK)
                .findFirst().orElseThrow();
        assertThat(tribalFinding.getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(tribalFinding.getAiGenerated()).isTrue();
        assertThat(tribalFinding.getId()).isNotNull(); // persisted
        assertThat(tribalFinding.getAffectedProcessIds().get(0).asText())
                .isEqualTo(tribalId.toString());

        assertThat(findings.stream()
                .filter(f -> f.getFindingType() == FindingType.UNDOCUMENTED_WORKFLOW)
                .findFirst().orElseThrow()
                .getSeverity()).isEqualTo(Severity.MEDIUM);
    }

    @Test
    void cleanDomainYieldsNoFindings() {
        UUID domainId = createDomain();

        Process documented = new Process();
        documented.setDomainId(domainId);
        documented.setName("Documented sign-off");
        documented.setDescription("Well documented");
        documented.setClassification(ManualProcessClassification.SIGN_OFF);
        documented.setDocumentationQuality(DocumentationQuality.DOCUMENTED);
        documented.setRiskRating(RiskRating.LOW);
        documented.setCreatedBy("test");
        processRepository.save(documented);

        assertThat(analyzer.analyzeManualProcesses(domainId)).isEmpty();
    }
}
