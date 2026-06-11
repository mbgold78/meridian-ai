package com.meridian.substrate.engine.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.Evidence;
import com.meridian.substrate.common.repo.EvidenceRepository;
import com.meridian.substrate.engine.ai.persona.PersonaContext;
import com.meridian.substrate.engine.ai.tools.impl.FindEvidenceTool;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@code find_evidence} (engine-spec diagnostic.md §7.1;
 * ADR-004 D6) in the shared tool-impl test shape: result vocabulary
 * ({evidenceId, entityId, entityType, label, sourceFile, confidence,
 * extractor}), the three repository paths, the hard in-domain constraint
 * (the tool must never teach the model evidence ids the grounding filter
 * would reject), and the limit contract (default 25, max 100).
 */
class FindEvidenceToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final EvidenceRepository evidenceRepository = mock(EvidenceRepository.class);
    private final FindEvidenceTool tool = new FindEvidenceTool(evidenceRepository, mapper);

    private final UUID domainId = UUID.randomUUID();
    private final UUID otherDomainId = UUID.randomUUID();

    @Test
    void domainWideQuery_returnsCitationVocabulary() {
        Evidence e = evidence(domainId, "system", "Committed from systems.csv",
                "glacier-systems.csv", 0.92f, "ingestion");
        when(evidenceRepository.findByDomainId(domainId)).thenReturn(List.of(e));

        JsonNode result = tool.execute(args(domainId, null, null, null), new PersonaContext());

        assertThat(result.isArray()).isTrue();
        assertThat(result).hasSize(1);
        JsonNode row = result.get(0);
        assertThat(row.path("evidenceId").asText()).isEqualTo(e.getId().toString());
        assertThat(row.path("entityId").asText()).isEqualTo(e.getEntityId().toString());
        assertThat(row.path("entityType").asText()).isEqualTo("system");
        assertThat(row.path("label").asText()).isEqualTo("Committed from systems.csv");
        assertThat(row.path("sourceFile").asText()).isEqualTo("glacier-systems.csv");
        assertThat(row.path("confidence").floatValue()).isEqualTo(0.92f);
        assertThat(row.path("extractor").asText()).isEqualTo("ingestion");
    }

    @Test
    void entityIdAndTypeQuery_usesCombinedLookup_andEnforcesDomain() {
        UUID entityId = UUID.randomUUID();
        Evidence inDomain = evidence(domainId, "process", "in-domain", "p.csv", null, "ingestion");
        inDomain.setEntityId(entityId);
        Evidence crossDomain = evidence(otherDomainId, "process", "cross-domain", "x.csv", null, "ingestion");
        crossDomain.setEntityId(entityId);
        when(evidenceRepository.findByEntityIdAndEntityType(entityId, "process"))
                .thenReturn(List.of(inDomain, crossDomain));

        JsonNode result = tool.execute(args(domainId, entityId, "process", null), new PersonaContext());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).path("label").asText()).isEqualTo("in-domain");
        assertThat(result.get(0).path("confidence").isNull())
                .as("absent confidence serializes as explicit null, not a fake 0")
                .isTrue();
    }

    @Test
    void entityIdOnlyQuery_excludesCrossDomainRows() {
        UUID entityId = UUID.randomUUID();
        Evidence inDomain = evidence(domainId, "system", "mine", "s.csv", 1.0f, "ingestion");
        Evidence crossDomain = evidence(otherDomainId, "system", "leak", "s2.csv", 1.0f, "ingestion");
        when(evidenceRepository.findByEntityId(entityId)).thenReturn(List.of(crossDomain, inDomain));

        JsonNode result = tool.execute(args(domainId, entityId, null, null), new PersonaContext());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).path("label").asText()).isEqualTo("mine");
    }

    @Test
    void entityTypeOnlyQuery_filtersDomainPoolByType() {
        Evidence system = evidence(domainId, "system", "a system", "s.csv", null, "seed");
        Evidence process = evidence(domainId, "process", "a process", "p.csv", null, "seed");
        when(evidenceRepository.findByDomainId(domainId)).thenReturn(List.of(system, process));

        JsonNode result = tool.execute(args(domainId, null, "process", null), new PersonaContext());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).path("entityType").asText()).isEqualTo("process");
    }

    @Test
    void limit_defaults25_capsAt100_ignoresNonPositive() {
        when(evidenceRepository.findByDomainId(domainId)).thenReturn(manyEvidenceRows(130));

        assertThat(tool.execute(args(domainId, null, null, null), new PersonaContext()))
                .as("default limit").hasSize(25);
        assertThat(tool.execute(args(domainId, null, null, 5), new PersonaContext()))
                .as("explicit limit").hasSize(5);
        assertThat(tool.execute(args(domainId, null, null, 250), new PersonaContext()))
                .as("max cap").hasSize(100);
        assertThat(tool.execute(args(domainId, null, null, 0), new PersonaContext()))
                .as("non-positive falls back to default").hasSize(25);
    }

    @Test
    void missingOrInvalidDomainId_returnsErrorNode() {
        JsonNode missing = tool.execute(mapper.createObjectNode(), new PersonaContext());
        assertThat(missing.path("error").asBoolean()).isTrue();
        assertThat(missing.path("message").asText()).contains("domainId is required");

        ObjectNode bad = mapper.createObjectNode();
        bad.put("domainId", "not-a-uuid");
        JsonNode invalid = tool.execute(bad, new PersonaContext());
        assertThat(invalid.path("error").asBoolean()).isTrue();
        assertThat(invalid.path("message").asText()).contains("not a valid UUID");
    }

    @Test
    void invalidEntityId_returnsErrorNode() {
        ObjectNode args = mapper.createObjectNode();
        args.put("domainId", domainId.toString());
        args.put("entityId", "garbage");
        JsonNode result = tool.execute(args, new PersonaContext());
        assertThat(result.path("error").asBoolean()).isTrue();
    }

    private ObjectNode args(UUID domain, UUID entityId, String entityType, Integer limit) {
        ObjectNode args = mapper.createObjectNode();
        args.put("domainId", domain.toString());
        if (entityId != null) args.put("entityId", entityId.toString());
        if (entityType != null) args.put("entityType", entityType);
        if (limit != null) args.put("limit", limit);
        return args;
    }

    private Evidence evidence(UUID domain, String entityType, String label,
                              String sourceFile, Float confidence, String extractor) {
        Evidence e = new Evidence();
        e.setId(UUID.randomUUID());
        e.setDomainId(domain);
        e.setEntityId(UUID.randomUUID());
        e.setEntityType(entityType);
        e.setLabel(label);
        e.setSourceFile(sourceFile);
        e.setConfidence(confidence);
        e.setExtractor(extractor);
        return e;
    }

    private List<Evidence> manyEvidenceRows(int n) {
        List<Evidence> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(evidence(domainId, "system", "row " + i, "f.csv", null, "ingestion"));
        }
        return rows;
    }
}
