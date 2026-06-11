package com.meridian.substrate.engine.ai.tools;

import com.meridian.substrate.common.ai.ToolCallRequest;
import com.meridian.substrate.engine.EngineTestApplication;
import com.meridian.substrate.engine.ai.persona.PersonaContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code find_evidence} registration + persona allowlist per the existing
 * {@code ToolRegistry} idiom (engine-spec diagnostic.md §7.1 / §2): the tool
 * is a Spring-discovered {@code Tool} bean; the analyst levels that hold the
 * other read tools (wildcard-allow personas: architect, auditor, L3_ANALYST)
 * hold it via the unchanged built-in defaults; restricted enumerated personas
 * (executive, sme, data_owner) do NOT silently gain it — their allow-lists
 * are untouched by the registration (the pinned-surface guard at the
 * persona-access layer).
 */
@SpringBootTest(classes = EngineTestApplication.class)
@ActiveProfiles("test")
class FindEvidenceToolRegistrationTest {

    @Autowired ToolRegistry toolRegistry;

    @Test
    void findEvidence_isRegisteredAsToolBean_withRequiredDomainId() {
        Tool tool = toolRegistry.getTool("find_evidence");
        assertThat(tool.name()).isEqualTo("find_evidence");
        assertThat(tool.parametersSchema().path("required"))
                .extracting(n -> n.asText())
                .containsExactly("domainId");
        assertThat(tool.parametersSchema().path("properties").has("entityId")).isTrue();
        assertThat(tool.parametersSchema().path("properties").has("entityType")).isTrue();
        assertThat(tool.parametersSchema().path("properties").has("limit")).isTrue();
    }

    @Test
    void analystLevels_holdFindEvidence_viaUnchangedDefaults() {
        for (String level : List.of("architect", "auditor", "L3_ANALYST")) {
            assertThat(toolRegistry.isAllowed(PersonaContext.ofLevel(level), "find_evidence"))
                    .as("analyst-grade level '%s' holds find_evidence", level)
                    .isTrue();
        }
        // The mode services' default PersonaContext is L3_ANALYST — the
        // validative/generative lanes can actually call the tool.
        List<ToolCallRequest.ToolDefinition> defs =
                toolRegistry.getToolDefinitionsForPersona(new PersonaContext());
        assertThat(defs).extracting(ToolCallRequest.ToolDefinition::name)
                .contains("find_evidence");
    }

    @Test
    void restrictedEnumeratedPersonas_doNotGainFindEvidence() {
        for (String level : List.of("executive", "sme", "data_owner")) {
            assertThat(toolRegistry.isAllowed(PersonaContext.ofLevel(level), "find_evidence"))
                    .as("enumerated allow-list persona '%s' must not silently gain the new tool", level)
                    .isFalse();
        }
    }

    @Test
    void techExecutive_wildcardExceptSemantics_unchangedByRegistration() {
        // tech_executive is allow-all-except(get_evidence_for_finding); new
        // tools default-allowed under the existing wildcard semantics — pinned
        // here as CURRENT behavior, not a new decision (any narrowing is a
        // persona-access config change, not code).
        PersonaContext techExecutive = PersonaContext.ofLevel("tech_executive");
        assertThat(toolRegistry.isAllowed(techExecutive, "get_evidence_for_finding")).isFalse();
        assertThat(toolRegistry.isAllowed(techExecutive, "find_evidence")).isTrue();
    }
}
