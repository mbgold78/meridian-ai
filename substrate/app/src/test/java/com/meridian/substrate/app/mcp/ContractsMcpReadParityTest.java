package com.meridian.substrate.app.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractParty;
import com.meridian.substrate.common.domain.enums.ContractStatus;
import com.meridian.substrate.common.repo.ContractPartyRepository;
import com.meridian.substrate.common.repo.ContractRepository;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §14.5 MCP suite, read half (engine-spec contracts.md §8.1/§8.2/§14.5):
 * a SCRIPTED STREAMABLE-HTTP CLIENT (the official SDK's
 * {@code HttpClientStreamableHttpTransport}, test scope) against the
 * {@code substrate-contracts} server mounted at {@code /mcp} — proving
 * {@code list_contracts}/{@code get_contract} parity with REST,
 * {@code get_obligations_for_principal} for a bound principal, plus the
 * remaining §8.2 read tools and the {@code check_action} advisory verdict.
 *
 * <p>Data = the DemoPack §14.1 fixture vectors seeded by
 * {@code ContractSeeder} (fixture source of truth
 * {@code C:/Users/mbgol/dev/meridian-homepoc/frontend/src/demo/beacon/contracts.ts},
 * copied into the seeder 2026-06-10 — the literals referenced below
 * [contract keys, the Curasync party label] are the seeder's, asserted
 * against BOTH surfaces, so fixture drift cannot silently pass). The
 * {@code substrate.demo.contracts.activate} lever flips
 * {@code co-tribal-knowledge-depopulation} ACTIVE so obligations are owed.
 *
 * <p>The principal binding ({@code contract_parties.mcp_principal_id}) is
 * test-arranged — pre-Phase-5 identity is the documented-spoofable
 * {@code X-MCP-Principal} header (spec §15 #12).
 *
 * <p>H2 fast lane; {@code PostgresContractsMcpReadParityIT} runs the same
 * flow on real Postgres in CI — H2-green is never acceptance (port-map §4.4).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "substrate.demo.seed=true",
        "substrate.demo.contracts.activate=true",
        "spring.datasource.url=jdbc:h2:mem:mcp_parity;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("dev")
class ContractsMcpReadParityTest {

    static final String CO_TRIBAL = "co-tribal-knowledge-depopulation";
    static final String FI_SHARED = "fi-shared-pre-fill-agent";
    static final String CURASYNC_LABEL = "Curasync Clinical Systems";
    static final String PRINCIPAL = "curasync-clinical-agent";

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    ContractRepository contractRepository;
    @Autowired
    ContractPartyRepository partyRepository;

    private final List<McpSyncClient> openClients = new ArrayList<>();
    private Contract coTribal;

    @BeforeEach
    void bindPrincipal() {
        coTribal = contractRepository.findByContractKey(CO_TRIBAL).get(0);
        assertThat(coTribal.getStatus())
                .as("activate lever precondition (§14.2)")
                .isEqualTo(ContractStatus.ACTIVE);
        for (ContractParty p : partyRepository
                .findByContractIdOrderBySequenceOrderAsc(coTribal.getId())) {
            if (CURASYNC_LABEL.equals(p.getPartyLabel())
                    && !PRINCIPAL.equals(p.getMcpPrincipalId())) {
                p.setMcpPrincipalId(PRINCIPAL);
                partyRepository.save(p);
            }
        }
    }

    @AfterEach
    void closeClients() {
        openClients.forEach(McpSyncClient::close);
        openClients.clear();
    }

    // -------------------------------------------------------------- the tests

    @Test
    void serverExposesTheSpec82ReadAndSpec83WriteToolInventory() {
        var tools = client(PRINCIPAL).listTools().tools().stream()
                .map(McpSchema.Tool::name).toList();
        // The FULL server inventory, pinned exactly: §8.2 read tools (this
        // suite) + the §8.3 governed write tools (behavior pinned by
        // ContractsMcpWriteTest, chunk 3c) + the agents.md §12 agent tools
        // (phase 6c — SAME server, no second one; behavior pinned by
        // AgentMcpToolsTest).
        assertThat(tools).containsExactlyInAnyOrder(
                "list_contracts", "get_contract", "get_commitments",
                "get_obligations_for_principal", "get_breaches",
                "get_enforcement_decisions", "check_action",
                "attest_commitment", "record_commitment_progress", "report_metric",
                "respond_to_contract", "invoke_decline",
                "acknowledge_breach", "mark_breach_remediated", "waive_breach",
                "list_agents", "get_agent", "get_my_registration", "get_my_control_state",
                "agent_heartbeat", "report_agent_activity", "acknowledge_control");
    }

    @Test
    void listContractsParityWithRest() {
        JsonNode restCards = restData("/api/v1/contracts?domainId=" + coTribal.getDomainId());
        JsonNode mcpCards = callOk(client(PRINCIPAL), "list_contracts",
                Map.of("domain_id", coTribal.getDomainId().toString())).get("contracts");
        assertThat(restCards.size()).isEqualTo(2);
        assertThat(mcpCards).isEqualTo(restCards);

        // §8.2 relaxations beyond REST: status + party filters.
        JsonNode activeOnly = callOk(client(PRINCIPAL), "list_contracts",
                Map.of("status", "ACTIVE")).get("contracts");
        assertThat(activeOnly.size()).isEqualTo(1);
        assertThat(activeOnly.get(0).get("contractKey").asText()).isEqualTo(CO_TRIBAL);
        JsonNode curasync = callOk(client(PRINCIPAL), "list_contracts",
                Map.of("party", "curasync")).get("contracts");
        assertThat(curasync.size()).isEqualTo(2);   // Curasync is a party to both vectors
    }

    @Test
    void getContractParityWithRestForBothFixtureVectors() {
        for (String key : List.of(CO_TRIBAL, FI_SHARED)) {
            JsonNode restDetail = restData("/api/v1/contracts/" + key);
            JsonNode mcpDetail = callOk(client(PRINCIPAL), "get_contract",
                    Map.of("contract_key", key));
            assertThat(mcpDetail).as("get_contract(%s) == REST detail", key)
                    .isEqualTo(restDetail);
        }
        // Vector 2 null-serialization pin (§14.1): estimatedValue PRESENT and null.
        JsonNode fiShared = callOk(client(PRINCIPAL), "get_contract",
                Map.of("contract_key", FI_SHARED));
        assertThat(fiShared.has("estimatedValue")).isTrue();
        assertThat(fiShared.get("estimatedValue").isNull()).isTrue();
        assertThat(fiShared.get("enforcement").get("monitorMechanism").asText())
                .startsWith("MCP federation contract id fi-shared-pre-fill-agent");
    }

    @Test
    void getObligationsForPrincipalForABoundPrincipal() {
        JsonNode obligations = callOk(client(PRINCIPAL),
                "get_obligations_for_principal", Map.of());
        assertThat(obligations.get("principalId").asText()).isEqualTo(PRINCIPAL);
        // Only the ACTIVE co-tribal binding shows: the fi-shared Curasync
        // party row is a different (unbound) row, and that contract is
        // PROPOSED anyway (§2.3 — nothing owed pre-ACTIVE).
        assertThat(obligations.get("parties").size()).isEqualTo(1);
        JsonNode party = obligations.get("parties").get(0);
        assertThat(party.get("contractKey").asText()).isEqualTo(CO_TRIBAL);
        assertThat(party.get("partyLabel").asText()).isEqualTo(CURASYNC_LABEL);
        assertThat(party.get("obligations").size()).isEqualTo(3);

        // The MONTHLY monthly-report commitment carries period arithmetic;
        // the other two are one-shots (ONE_TIME).
        List<JsonNode> monthly = new ArrayList<>();
        List<JsonNode> oneTime = new ArrayList<>();
        party.get("obligations").forEach(o -> {
            if ("MONTHLY".equals(o.path("cadence").asText(null))) {
                monthly.add(o);
            } else {
                oneTime.add(o);
            }
        });
        assertThat(monthly).hasSize(1);
        assertThat(monthly.get(0).get("nextPeriodKey").asText()).matches("\\d{4}-\\d{2}");
        assertThat(monthly.get(0).get("lastClosedPeriodKey").asText()).matches("\\d{4}-\\d{2}");
        assertThat(monthly.get(0).get("missingAttestation").asBoolean())
                .as("no attestations are seeded — the last closed period is unattested")
                .isTrue();
        assertThat(oneTime).hasSize(2);
        oneTime.forEach(o ->
                assertThat(o.get("nextPeriodKey").asText()).isEqualTo("ONE_TIME"));
    }

    @Test
    void obligationsWithoutPrincipalHeaderIsAStructuredToolError() {
        McpSchema.CallToolResult result = client(null).callTool(
                new McpSchema.CallToolRequest("get_obligations_for_principal", Map.of()));
        assertThat(result.isError()).isTrue();
        JsonNode structured = objectMapper.valueToTree(result.structuredContent());
        assertThat(structured.get("code").asText()).isEqualTo("PRINCIPAL_REQUIRED");
        assertThat(structured.get("message").asText()).contains("X-MCP-Principal");
    }

    @Test
    void checkActionAdvisoryAllowsWhenNoGuardsGovernTheAction() {
        // Both fixture contracts compile to ZERO guards (spec §16 pin) — the
        // advisory PDP dry-run must come back a clean ALLOW, and being a READ
        // tool it never rows an enforcement decision.
        JsonNode verdict = callOk(client(PRINCIPAL), "check_action",
                Map.of("action_type", "agent.promote",
                        "payload", Map.of("scope", "hipaa-attestation-forms")));
        assertThat(verdict.get("verdict").asText()).isEqualTo("ALLOW");
        assertThat(verdict.get("matchedClauses")).isEmpty();
        assertThat(restData("/api/v1/contracts/" + CO_TRIBAL + "/decisions"))
                .as("check_action is counter-only — no decision rows (§8.2)")
                .isEmpty();
    }

    @Test
    void remainingReadLanesSurfaceExplicitContent() {
        McpSyncClient c = client(PRINCIPAL);
        // 3 + 3 + 2 commitments across the three co-tribal parties (§14.1).
        JsonNode commitments = callOk(c, "get_commitments",
                Map.of("contract_key", CO_TRIBAL)).get("commitments");
        assertThat(commitments.size()).isEqualTo(8);
        assertThat(commitments.get(0).get("complianceState").asText()).isEqualTo("UNEVALUATED");
        // Zero-yield lanes surface as explicit empty arrays, never absent keys.
        assertThat(callOk(c, "get_breaches", Map.of()).get("breaches"))
                .isNotNull().isEmpty();
        assertThat(callOk(c, "get_enforcement_decisions", Map.of()).get("decisions"))
                .isNotNull().isEmpty();
    }

    @Test
    void unknownContractKeyIsAStructuredNotFoundError() {
        McpSchema.CallToolResult result = client(PRINCIPAL).callTool(
                new McpSchema.CallToolRequest("get_contract",
                        Map.of("contract_key", "no-such-contract")));
        assertThat(result.isError()).isTrue();
        JsonNode structured = objectMapper.valueToTree(result.structuredContent());
        assertThat(structured.get("code").asText()).isEqualTo("CONTRACT_NOT_FOUND");
    }

    // -------------------------------------------------------------- plumbing

    /** Scripted Streamable-HTTP client (§14.5); principal=null sends no identity header. */
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
                .clientInfo(new McpSchema.Implementation("contracts-parity-suite", "0.1.0"))
                .requestTimeout(Duration.ofSeconds(30))
                .build();
        client.initialize();
        openClients.add(client);
        return client;
    }

    private JsonNode callOk(McpSyncClient client, String tool, Map<String, Object> args) {
        McpSchema.CallToolResult result =
                client.callTool(new McpSchema.CallToolRequest(tool, args));
        assertThat(result.isError()).as("%s isError", tool).isNotEqualTo(Boolean.TRUE);
        return objectMapper.valueToTree(result.structuredContent());
    }

    /** REST lane: unwraps the {@code {data, meta, errors}} envelope. */
    private JsonNode restData(String path) {
        ResponseEntity<String> response = rest.getForEntity(path, String.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("GET %s -> %s", path, response.getStatusCode()).isTrue();
        try {
            JsonNode envelope = objectMapper.readTree(response.getBody());
            assertThat(envelope.get("errors")).isEmpty();
            return envelope.get("data");
        } catch (Exception e) {
            throw new IllegalStateException("unparseable envelope from " + path, e);
        }
    }
}
