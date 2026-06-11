package com.meridian.substrate.app.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.app.agent.AgentVerbTestSupport;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.enums.AgentInterventionStatus;
import com.meridian.substrate.common.domain.enums.AgentSightingChannel;
import com.meridian.substrate.common.domain.enums.GatewayDecision;
import com.meridian.substrate.common.repo.AgentInterventionRepository;
import com.meridian.substrate.common.repo.AgentMetricSampleRepository;
import com.meridian.substrate.common.repo.AgentSightingRepository;
import com.meridian.substrate.common.repo.ContractEnforcementDecisionRepository;
import com.meridian.substrate.engine.agent.AgentGatewaySignalService;
import com.meridian.substrate.engine.agent.AgentQueryService;
import com.meridian.substrate.engine.contract.ContractProperties;
import com.meridian.substrate.engine.contract.gateway.AgentGateDeniedException;
import com.meridian.substrate.engine.contract.gateway.AgentRegistryGate;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The §16.1 row-6c MCP acceptance beats over a REAL Streamable-HTTP client
 * (engine-spec agents.md §12/§16.1; ADR-003 D16) — the SAME
 * {@code substrate-contracts} server, no second one:
 *
 * <ul>
 *   <li>inventory: the seven §12 agent tools beside the contract tools;</li>
 *   <li>{@code get_my_registration} returns the caller's VERBATIM mandate;</li>
 *   <li>shadow: heartbeat as a PAUSED agent → sighting + WOULD_DENY rowed +
 *       ACTIVE_WHILE_PAUSED in signals (the tool itself succeeds — shadow
 *       never blocks);</li>
 *   <li>enforce: the same heartbeat → structured {@code isError} with code
 *       {@code AGENT_GATE_DENIAL}, reason {@code AGENT_CONTROL_SUSPENDED};</li>
 *   <li>kill then heartbeat → {@code SEEN_AFTER_KILL};</li>
 *   <li>{@code acknowledge_control} stays available while suspended;</li>
 *   <li>throttle 2/window → the third governed call is
 *       {@code AGENT_BUDGET_EXHAUSTED};</li>
 *   <li>{@code report_agent_activity} lands samples (adherence) +
 *       sightings; unregistered principals get the honest
 *       {@code AGENT_NOT_REGISTERED} shape.</li>
 * </ul>
 *
 * <p>H2 fast lane; {@code PostgresAgentGatewayIT} is the CI gate.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "substrate.contracts.enforcement.mode=shadow",
        "substrate.contracts.monitor.sweep-interval=PT1H",
        "spring.datasource.url=jdbc:h2:mem:agent_mcp_tools;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AgentMcpToolsTest extends AgentVerbTestSupport {

    @LocalServerPort
    int port;
    @Autowired ObjectMapper objectMapper;
    @Autowired ContractProperties contractProperties;
    @Autowired AgentSightingRepository sightings;
    @Autowired AgentInterventionRepository interventions;
    @Autowired AgentMetricSampleRepository samples;
    @Autowired ContractEnforcementDecisionRepository decisions;
    @Autowired AgentQueryService queryService;

    private final List<McpSyncClient> openClients = new ArrayList<>();

    @AfterEach
    void tearDown() {
        contractProperties.getEnforcement().setMode("shadow");
        openClients.forEach(McpSyncClient::close);
        openClients.clear();
    }

    @Test
    void serverExposesTheSpec12AgentToolInventory_besideTheContractTools() {
        var tools = client("inventory-agent").listTools().tools().stream()
                .map(McpSchema.Tool::name).toList();
        assertThat(tools).contains(
                // §12 agent tools …
                "list_agents", "get_agent", "get_my_registration", "get_my_control_state",
                "agent_heartbeat", "report_agent_activity", "acknowledge_control",
                // … on the SAME server as the §8.2/§8.3 contract tools (D16)
                "list_contracts", "attest_commitment", "report_metric");
        // lifecycle tools DO NOT exist (I11 — agents never self-administer)
        assertThat(tools).noneMatch(name -> name.contains("deploy") || name.contains("promote")
                || name.contains("pause") || name.contains("kill") || name.contains("retire"));
    }

    @Test
    void getMyRegistrationReturnsTheVerbatimMandate() throws Exception {
        AgentRegistration reg = deploy(UUID.randomUUID(), "a-pre-visit");
        bind(reg, "mcp-mandate-agent");

        JsonNode detail = callOk(client("mcp-mandate-agent"), "get_my_registration", Map.of());

        // VERBATIM mandate — byte-identical to the deploy body (I2)
        assertThat(detail.get("deploymentCriteria").asText())
                .isEqualTo("Pre-fill intake forms from prior-visit data,"
                        + " target 90% pre-fill accuracy");
        assertThat(detail.get("status").asText()).isEqualTo("shadow");
        assertThat(detail.get("oversightChain").get(0).asText()).isEqualTo(POSITION_1);
        assertThat(detail.get("criteriaGates").get(0).get("gateText").asText())
                .isEqualTo("target 90% pre-fill accuracy");
        assertThat(detail.get("criteriaGates").get(0).get("standing").asText())
                .isEqualTo("unevaluated");
    }

    @Test
    void shadowHeartbeatAsPausedAgent_sightedWouldDeniedAndSignalled() throws Exception {
        AgentRegistration reg = deploy(UUID.randomUUID(), "a-msg-router");
        bind(reg, "mcp-paused-shadow-agent");
        pause(reg, "Paused pending Compliance review of PHI-routing change");

        JsonNode result = callOk(client("mcp-paused-shadow-agent"), "agent_heartbeat",
                Map.of("activity_summary", "overnight router still polling"));

        assertThat(result.get("recorded").asBoolean()).isTrue();   // shadow never blocks
        assertThat(sightings.findByAgentRegistrationIdOrderBySeenAtDesc(reg.getId()))
                .anyMatch(s -> s.getChannel() == AgentSightingChannel.GATEWAY_ACTION);
        assertThat(decisions.findByDomainIdAndDecision(reg.getDomainId(),
                GatewayDecision.WOULD_DENY))
                .anyMatch(d -> AgentRegistryGate.REASON_CONTROL_SUSPENDED.equals(d.getReason()));
        assertThat(queryService.signals(reg.getDomainId()).controlViolations())
                .anyMatch(v -> "ACTIVE_WHILE_PAUSED".equals(v.kind()));
    }

    @Test
    void enforceHeartbeatAsPausedAgent_structuredAgentGateDenialIsError() throws Exception {
        AgentRegistration reg = deploy(UUID.randomUUID(), "a-msg-router");
        bind(reg, "mcp-paused-enforce-agent");
        pause(reg, "Paused pending review");
        contractProperties.getEnforcement().setMode("enforce");

        McpSchema.CallToolResult result = client("mcp-paused-enforce-agent").callTool(
                new McpSchema.CallToolRequest("agent_heartbeat", Map.of()));

        assertThat(result.isError()).isTrue();
        JsonNode denial = structured(result);
        assertThat(denial.get("code").asText())
                .isEqualTo(AgentGateDeniedException.AGENT_GATE_CODE);
        assertThat(denial.get("reason").asText())
                .isEqualTo(AgentRegistryGate.REASON_CONTROL_SUSPENDED);
        assertThat(denial.get("decisionId").asText()).isNotBlank();
    }

    @Test
    void killThenHeartbeat_seenAfterKill() throws Exception {
        AgentRegistration reg = deploy(UUID.randomUUID(), "a-noshow");
        bind(reg, "mcp-killed-agent");
        postVerb(reg.getId() + "/kill", reasonedBody("Emergency stop"))
                .andExpect(status().isOk());
        contractProperties.getEnforcement().setMode("enforce");

        McpSchema.CallToolResult result = client("mcp-killed-agent").callTool(
                new McpSchema.CallToolRequest("agent_heartbeat", Map.of()));

        assertThat(result.isError()).isTrue();
        assertThat(interventions
                .findByAgentRegistrationIdAndStatus(reg.getId(), AgentInterventionStatus.OPEN))
                .anyMatch(i -> AgentGatewaySignalService.SOURCE_SEEN_AFTER_KILL
                        .equals(i.getSourceKey()));
        assertThat(queryService.signals(reg.getDomainId()).controlViolations())
                .anyMatch(v -> "SEEN_AFTER_KILL".equals(v.kind()));
    }

    @Test
    void acknowledgeControlIsTheOneRetainedWriteWhileSuspended() throws Exception {
        AgentRegistration reg = deploy(UUID.randomUUID(), "a-eligibility");
        bind(reg, "mcp-compliant-agent");
        pause(reg, "Paused pending review");
        contractProperties.getEnforcement().setMode("enforce");

        JsonNode ack = callOk(client("mcp-compliant-agent"), "acknowledge_control", Map.of());

        assertThat(ack.get("pendingVerb").asText()).isEqualTo("pause");
        assertThat(ack.get("acknowledgedAt").asText()).isNotBlank();
        AgentRegistration after = registrations.findById(reg.getId()).orElseThrow();
        assertThat(after.getControlAcknowledgedAt()).isNotNull();
        assertThat(after.getControlConfirmedAt()).isNull();   // ack ≠ confirmation (D6)
    }

    @Test
    void throttledAgent_thirdGovernedCallIsBudgetExhausted() throws Exception {
        AgentRegistration reg = deploy(UUID.randomUUID(), "a-coding");
        bind(reg, "mcp-throttled-agent");
        ObjectNode throttle = verbBody();
        throttle.put("budgetKind", "ACTIONS_PER_WINDOW");
        throttle.put("limitValue", 2);
        throttle.put("windowMinutes", 60);
        postVerb(reg.getId() + "/throttle", throttle).andExpect(status().isOk());
        contractProperties.getEnforcement().setMode("enforce");

        McpSyncClient agent = client("mcp-throttled-agent");
        callOk(agent, "agent_heartbeat", Map.of("activity_summary", "1"));   // 1
        callOk(agent, "agent_heartbeat", Map.of("activity_summary", "2"));   // 2
        McpSchema.CallToolResult third = agent.callTool(
                new McpSchema.CallToolRequest("agent_heartbeat", Map.of()));  // 3 > limit

        assertThat(third.isError()).isTrue();
        JsonNode denial = structured(third);
        assertThat(denial.get("code").asText())
                .isEqualTo(AgentGateDeniedException.AGENT_GATE_CODE);
        assertThat(denial.get("reason").asText())
                .isEqualTo(AgentRegistryGate.REASON_BUDGET_EXHAUSTED);
        // §13.1: surfaced as a signal, never silent
        assertThat(queryService.signals(reg.getDomainId()).budgetsExhausted())
                .anyMatch(b -> reg.getAgentKey().equals(b.agentKey()));
    }

    @Test
    void reportAgentActivityLandsSamplesAndSightings() throws Exception {
        AgentRegistration reg = deploy(UUID.randomUUID(), "a-pre-visit");
        bind(reg, "mcp-reporting-agent");

        callOk(client("mcp-reporting-agent"), "report_agent_activity", Map.of(
                "activity_note", "processed 412 intake forms",
                "metrics", List.of(Map.of(
                        "metric_key", "adherence", "value", 96, "period_key", "2026-06"))));

        assertThat(samples.findByAgentRegistrationIdAndMetricKeyAndPeriodKey(
                reg.getId(), "adherence", "2026-06"))
                .isPresent()
                .get().satisfies(s -> assertThat(s.getMetricValue()).isEqualTo(96.0));
        // the engine now COMPUTES what the fixture used to stamp (§9.2)
        JsonNode detail = callOk(client("mcp-reporting-agent"), "get_my_registration", Map.of());
        assertThat(detail.get("adherence").asDouble()).isEqualTo(96.0);
    }

    @Test
    void unregisteredPrincipal_getsTheHonestRegistrationRequiredShape() {
        McpSchema.CallToolResult result = client("never-registered-agent").callTool(
                new McpSchema.CallToolRequest("agent_heartbeat", Map.of()));
        assertThat(result.isError()).isTrue();
        assertThat(structured(result).get("code").asText()).isEqualTo("AGENT_NOT_REGISTERED");

        McpSchema.CallToolResult noHeader = client(null).callTool(
                new McpSchema.CallToolRequest("get_my_registration", Map.of()));
        assertThat(noHeader.isError()).isTrue();
        assertThat(structured(noHeader).get("code").asText()).isEqualTo("PRINCIPAL_REQUIRED");
    }

    // ------------------------------------------------------------------ helpers

    private void bind(AgentRegistration reg, String principalId) throws Exception {
        ObjectNode body = verbBody();
        body.put("mcpPrincipalId", principalId);
        postVerb(reg.getId() + "/principal", body).andExpect(status().isOk());
    }

    private void pause(AgentRegistration reg, String reason) throws Exception {
        postVerb(reg.getId() + "/pause", reasonedBody(reason)).andExpect(status().isOk());
    }

    private McpSyncClient client(String principal) {
        var transport = HttpClientStreamableHttpTransport
                .builder("http://localhost:" + port)
                .endpoint(ContractsMcpConfig.MCP_ENDPOINT)
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .httpRequestCustomizer((builder, method, uri, body, context) -> {
                    if (principal != null) {
                        builder.header(ContractsMcpConfig.PRINCIPAL_HEADER, principal);
                    }
                })
                .build();
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("agent-tools-suite", "0.1.0"))
                .requestTimeout(Duration.ofSeconds(30))
                .build();
        client.initialize();
        openClients.add(client);
        return client;
    }

    private JsonNode callOk(McpSyncClient client, String tool, Map<String, Object> args) {
        McpSchema.CallToolResult result =
                client.callTool(new McpSchema.CallToolRequest(tool, args));
        assertThat(result.isError())
                .as("%s isError (content: %s)", tool, result.content())
                .isNotEqualTo(Boolean.TRUE);
        return structured(result);
    }

    private JsonNode structured(McpSchema.CallToolResult result) {
        return objectMapper.valueToTree(result.structuredContent());
    }
}
