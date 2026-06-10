package com.meridian.substrate.engine.decision.impact;

import java.util.List;

/**
 * Structured result of an impact analysis, including system and process impacts
 * along with an overall recommendation.
 */
public record ImpactAnalysisResult(
    String summary,
    List<SystemImpact> systemImpacts,
    List<ProcessImpact> processImpacts,
    String recommendation
) {

    public record SystemImpact(String systemId, String systemName, String impactType, String rationale) {}

    public record ProcessImpact(String processId, String processName,
        String impactType, String rationale, String mitigation) {}
}
