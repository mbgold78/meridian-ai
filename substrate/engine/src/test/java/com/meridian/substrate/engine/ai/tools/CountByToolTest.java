package com.meridian.substrate.engine.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.Finding;
import com.meridian.substrate.common.domain.enums.Severity;
import com.meridian.substrate.common.repo.FindingRepository;
import com.meridian.substrate.common.repo.ProcessRepository;
import com.meridian.substrate.common.repo.SystemRepository;
import com.meridian.substrate.engine.ai.persona.PersonaContext;
import com.meridian.substrate.engine.ai.tools.impl.CountByTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Representative tool-impl unit test (the 10 tools share the same shape). */
class CountByToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SystemRepository systemRepository = mock(SystemRepository.class);
    private final FindingRepository findingRepository = mock(FindingRepository.class);
    private final ProcessRepository processRepository = mock(ProcessRepository.class);
    private final CountByTool tool = new CountByTool(
            systemRepository, findingRepository, processRepository, mapper);

    @Test
    void countsFindingsBySeverity() {
        when(findingRepository.findAll()).thenReturn(List.of(
                findingWithSeverity(Severity.HIGH),
                findingWithSeverity(Severity.HIGH),
                findingWithSeverity(Severity.LOW),
                findingWithSeverity(null)));

        ObjectNode args = mapper.createObjectNode();
        args.put("entityType", "findings");
        args.put("groupBy", "severity");

        JsonNode result = tool.execute(args, new PersonaContext());

        assertThat(result.path("entityType").asText()).isEqualTo("findings");
        assertThat(result.path("groupBy").asText()).isEqualTo("severity");
        assertThat(result.path("counts").path("HIGH").asLong()).isEqualTo(2);
        assertThat(result.path("counts").path("LOW").asLong()).isEqualTo(1);
        assertThat(result.path("counts").path("UNKNOWN").asLong()).isEqualTo(1);
        assertThat(result.path("total").asLong()).isEqualTo(4);
    }

    @Test
    void missingArguments_returnErrorNode() {
        JsonNode result = tool.execute(mapper.createObjectNode(), new PersonaContext());
        assertThat(result.path("error").asBoolean()).isTrue();
    }

    @Test
    void unknownEntityType_returnsErrorNode() {
        ObjectNode args = mapper.createObjectNode();
        args.put("entityType", "widgets");
        args.put("groupBy", "color");

        JsonNode result = tool.execute(args, new PersonaContext());
        assertThat(result.path("error").asBoolean()).isTrue();
        assertThat(result.path("message").asText()).contains("Unknown entityType");
    }

    private Finding findingWithSeverity(Severity severity) {
        Finding f = new Finding();
        f.setSeverity(severity);
        return f;
    }
}
