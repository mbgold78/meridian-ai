package com.meridian.substrate.engine.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.ai.ToolCallRequest;
import com.meridian.substrate.engine.ai.persona.PersonaContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the configuration-driven persona→tool access rules
 * ({@code substrate.engine.tools.persona-access.*}) and their built-in
 * homepoc-parity defaults.
 */
class ToolRegistryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ToolRegistry registryWith(ToolExecutionProperties props) {
        List<Tool> tools = List.of(
                named("count_by"), named("find_systems"), named("find_findings"),
                named("get_decision_log"), named("get_evidence_for_finding"),
                named("find_canonical_entities"), named("get_system_profile"));
        return new ToolRegistry(tools, props, mapper);
    }

    @Test
    void builtInDefaults_replicateHomepocAllowlist() {
        ToolRegistry registry = registryWith(new ToolExecutionProperties());

        PersonaContext architect = PersonaContext.ofLevel("architect");
        PersonaContext auditor = PersonaContext.ofLevel("auditor");
        PersonaContext analyst = PersonaContext.ofLevel("L3_ANALYST");
        PersonaContext executive = PersonaContext.ofLevel("executive");
        PersonaContext sme = PersonaContext.ofLevel("sme");
        PersonaContext dataOwner = PersonaContext.ofLevel("data_owner");
        PersonaContext techExecutive = PersonaContext.ofLevel("tech_executive");
        PersonaContext unknown = PersonaContext.ofLevel("totally_unknown");

        // all-tools personas
        for (PersonaContext p : List.of(architect, auditor, analyst, unknown)) {
            assertThat(registry.getToolsForPersona(p)).hasSize(7);
        }

        // executive — limited set
        assertThat(registry.getToolsForPersona(executive))
                .extracting(Tool::name)
                .containsExactlyInAnyOrder("count_by", "find_findings", "get_decision_log");

        // sme — specific tools
        assertThat(registry.getToolsForPersona(sme))
                .extracting(Tool::name)
                .containsExactlyInAnyOrder("find_findings", "get_evidence_for_finding",
                        "find_canonical_entities");

        // data_owner — single tool
        assertThat(registry.getToolsForPersona(dataOwner))
                .extracting(Tool::name)
                .containsExactly("get_system_profile");

        // tech_executive — all minus evidence drill-down
        assertThat(registry.isAllowed(techExecutive, "get_evidence_for_finding")).isFalse();
        assertThat(registry.getToolsForPersona(techExecutive)).hasSize(6);
    }

    @Test
    void configuredAccess_overridesBuiltInDefaults() {
        ToolExecutionProperties props = new ToolExecutionProperties();
        props.setPersonaAccess(Map.of(
                "executive", ToolExecutionProperties.PersonaToolAccess.allowOnly("find_systems")));
        ToolRegistry registry = registryWith(props);

        PersonaContext executive = PersonaContext.ofLevel("executive");
        assertThat(registry.isAllowed(executive, "find_systems")).isTrue();
        assertThat(registry.isAllowed(executive, "count_by"))
                .as("configured rule replaces the built-in default for that level")
                .isFalse();

        // Other levels keep their built-in defaults.
        assertThat(registry.isAllowed(PersonaContext.ofLevel("data_owner"), "get_system_profile")).isTrue();
    }

    @Test
    void denyList_beatsWildcardAllow() {
        ToolExecutionProperties props = new ToolExecutionProperties();
        props.setPersonaAccess(Map.of(
                "auditor", ToolExecutionProperties.PersonaToolAccess.allowAllExcept("count_by")));
        ToolRegistry registry = registryWith(props);

        PersonaContext auditor = PersonaContext.ofLevel("auditor");
        assertThat(registry.isAllowed(auditor, "count_by")).isFalse();
        assertThat(registry.isAllowed(auditor, "find_systems")).isTrue();
    }

    @Test
    void toolDefinitions_areFilteredAndCarrySchema() {
        ToolRegistry registry = registryWith(new ToolExecutionProperties());
        List<ToolCallRequest.ToolDefinition> defs =
                registry.getToolDefinitionsForPersona(PersonaContext.ofLevel("data_owner"));

        assertThat(defs).hasSize(1);
        assertThat(defs.get(0).name()).isEqualTo("get_system_profile");
        assertThat(defs.get(0).inputSchema()).containsEntry("type", "object");
    }

    @Test
    void unknownToolLookup_throws() {
        ToolRegistry registry = registryWith(new ToolExecutionProperties());
        assertThatThrownBy(() -> registry.getTool("nope"))
                .hasMessageContaining("Unknown tool: nope");
    }

    private Tool named(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "test tool " + name;
            }

            @Override
            public JsonNode parametersSchema() {
                ObjectNode schema = mapper.createObjectNode();
                schema.put("type", "object");
                schema.putObject("properties");
                return schema;
            }

            @Override
            public JsonNode execute(JsonNode arguments, PersonaContext persona) {
                return mapper.createObjectNode().put("tool", name);
            }
        };
    }
}
