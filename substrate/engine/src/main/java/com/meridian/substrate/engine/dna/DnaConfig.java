package com.meridian.substrate.engine.dna;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Project DNA — the identity and scope contract for this substrate instance.
 * Driven by {@code substrate.dna.*} application properties. The DNA preamble
 * is injected into every LLM system prompt so the model always knows the
 * engagement context without repeating it in user messages.
 *
 * <p><b>Port note (fixture seam S10):</b> homepoc's code defaults baked the
 * demo identity ("Meridian Bancorp · Risk Consolidation", SR 11-7/Basel/SOX).
 * Substrate code defaults are engagement-neutral; per-engagement identity MUST
 * come from {@code substrate.dna.*} configuration (and, longer-term, the
 * engagement row). Missing keys never break boot — the neutral defaults below
 * apply. The demo identity values belong in the dev/demo profile yml, not here.
 */
@Component
@ConfigurationProperties(prefix = "substrate.dna")
public class DnaConfig {

    private String projectName = "Unconfigured Engagement";
    private String domain = "general";
    private String primaryObjective =
            "Provide evidence-grounded analysis of the engagement's systems, processes, and findings.";
    private List<String> entityTypes = List.of("system", "manual-process", "canonical-entity", "finding", "integration");
    private List<String> regulatoryContext = List.of();
    private String reductionMode = "AGGRESSIVE";
    private String targetHorizonMonths = "18";

    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }

    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }

    public String getPrimaryObjective() { return primaryObjective; }
    public void setPrimaryObjective(String primaryObjective) { this.primaryObjective = primaryObjective; }

    public List<String> getEntityTypes() { return entityTypes; }
    public void setEntityTypes(List<String> entityTypes) { this.entityTypes = entityTypes; }

    public List<String> getRegulatoryContext() { return regulatoryContext; }
    public void setRegulatoryContext(List<String> regulatoryContext) { this.regulatoryContext = regulatoryContext; }

    public String getReductionMode() { return reductionMode; }
    public void setReductionMode(String reductionMode) { this.reductionMode = reductionMode; }

    public String getTargetHorizonMonths() { return targetHorizonMonths; }
    public void setTargetHorizonMonths(String targetHorizonMonths) { this.targetHorizonMonths = targetHorizonMonths; }
}
