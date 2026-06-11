package com.meridian.substrate.app.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.app.mcp.ContractsMcpConfig;
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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract engine chunk 3a-3 Postgres gate (engine-spec contracts.md §14.5,
 * read half, over real Postgres 16): the §13 3a MCP acceptance — a scripted
 * Streamable-HTTP client against the {@code substrate-contracts} server at
 * {@code /mcp} proving {@code list_contracts}/{@code get_contract} parity
 * with REST, {@code get_obligations_for_principal} for a bound principal,
 * and the {@code check_action} advisory verdict — against the DemoPack
 * §14.1 vectors seeded through the real registry path (V5+V5_1 under
 * {@code ddl-auto=validate}).
 *
 * <p>The full assertion matrix lives in the H2 fast-lane twin
 * ({@code app.mcp.ContractsMcpReadParityTest}); this gate re-proves the
 * load-bearing core on the real dialect because H2-green is never acceptance
 * (port-map §4.4).
 *
 * <p><b>Docker-less machines (e.g. the Windows dev box):</b> self-disables
 * via {@code @Testcontainers(disabledWithoutDocker = true)}; CI
 * (ubuntu-latest) runs the real gate.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "substrate.demo.seed=true",
        "substrate.demo.contracts.activate=true"
})
class PostgresContractsMcpReadParityIT {

    static final String CO_TRIBAL = "co-tribal-knowledge-depopulation";
    static final String CURASYNC_LABEL = "Curasync Clinical Systems";
    static final String PRINCIPAL = "curasync-clinical-agent";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

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
        assertThat(coTribal.getStatus()).isEqualTo(ContractStatus.ACTIVE);
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

    @Test
    void mcpReadSurfaceServesTheSeededVectorsOnPostgres() throws Exception {
        McpSyncClient client = client(PRINCIPAL);

        // §8.2 read inventory (+ the §8.3 governed write tools, mounted 3c —
        // behavior pinned by ContractsMcpWriteTest; exact-set pin lives in
        // ContractsMcpReadParityTest).
        assertThat(client.listTools().tools().stream().map(McpSchema.Tool::name).toList())
                .contains("list_contracts", "get_contract",
                        "get_commitments", "get_obligations_for_principal", "get_breaches",
                        "get_enforcement_decisions", "check_action",
                        "attest_commitment", "report_metric", "invoke_decline", "waive_breach");

        // list_contracts / get_contract parity with REST (§14.5).
        JsonNode restCards = restData("/api/v1/contracts?domainId=" + coTribal.getDomainId());
        assertThat(callOk(client, "list_contracts",
                Map.of("domain_id", coTribal.getDomainId().toString())).get("contracts"))
                .isEqualTo(restCards);
        assertThat(callOk(client, "get_contract", Map.of("contract_key", CO_TRIBAL)))
                .isEqualTo(restData("/api/v1/contracts/" + CO_TRIBAL));

        // get_obligations_for_principal for the bound principal (§14.5).
        JsonNode obligations = callOk(client, "get_obligations_for_principal", Map.of());
        assertThat(obligations.get("principalId").asText()).isEqualTo(PRINCIPAL);
        assertThat(obligations.get("parties").size()).isEqualTo(1);
        JsonNode party = obligations.get("parties").get(0);
        assertThat(party.get("contractKey").asText()).isEqualTo(CO_TRIBAL);
        assertThat(party.get("obligations").size()).isEqualTo(3);

        // check_action advisory: zero guards on the fixtures (spec §16) → ALLOW.
        JsonNode verdict = callOk(client, "check_action",
                Map.of("action_type", "agent.promote"));
        assertThat(verdict.get("verdict").asText()).isEqualTo("ALLOW");
        assertThat(verdict.get("matchedClauses")).isEmpty();
    }

    // -------------------------------------------------------------- plumbing

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
                .clientInfo(new McpSchema.Implementation("contracts-parity-suite-pg", "0.1.0"))
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

    private JsonNode restData(String path) throws Exception {
        ResponseEntity<String> response = rest.getForEntity(path, String.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("GET %s -> %s", path, response.getStatusCode()).isTrue();
        JsonNode envelope = objectMapper.readTree(response.getBody());
        assertThat(envelope.get("errors")).isEmpty();
        return envelope.get("data");
    }
}
