package com.meridian.substrate.app.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.app.mcp.ContractsMcpConfig;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractCommitment;
import com.meridian.substrate.common.domain.ContractParty;
import com.meridian.substrate.common.repo.ContractAttestationRepository;
import com.meridian.substrate.common.repo.ContractCommitmentRepository;
import com.meridian.substrate.common.repo.ContractPartyRepository;
import com.meridian.substrate.common.repo.ContractRepository;
import com.meridian.substrate.engine.contract.monitor.PeriodKeys;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * MCP principal binding under oidc (ADR-002 D8 / agents.md §8 — the revisit
 * hook landed): {@code /mcp} sits behind the resource-server chain, the
 * {@code X-MCP-Principal} header is DEAD, and the principal handed to tool
 * handlers is the VERIFIED client-credential token's {@code sub} — matched by
 * the existing §8.2 ladder against {@code contract_parties.mcp_principal_id}.
 *
 * <ul>
 *   <li>unauthenticated MCP → HTTP 401 (even with a spoofed principal
 *       header);</li>
 *   <li>governed write records the token sub as actor
 *       ({@code attested_by})— a spoofed header riding alongside changes
 *       nothing;</li>
 *   <li>party-scoped tools resolve the caller's party through the sub
 *       binding.</li>
 * </ul>
 *
 * <p>JWT-mock lane (mock {@link JwtDecoder}; Docker-less dev box) — the real
 * Keycloak issuer round-trip is {@code KeycloakOidcRoundTripIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "substrate.auth.mode=oidc",
        "substrate.demo.seed=true",
        "substrate.demo.contracts.activate=true",
        "substrate.contracts.monitor.sweep-interval=PT1H",
        "spring.datasource.url=jdbc:h2:mem:oidc_mcp;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("dev")
class OidcMcpPrincipalBindingTest {

    static final String CO_TRIBAL = "co-tribal-knowledge-depopulation";
    /** Keycloak client-credentials shape: sub = service-account user id. */
    static final String AGENT_SUB = "b2a7c4e1-9d3f-4a6b-8c5d-1e0f7a9b3c2d";
    static final String AGENT_TOKEN = "agent-token";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper objectMapper;
    @Autowired ContractRepository contracts;
    @Autowired ContractPartyRepository parties;
    @Autowired ContractCommitmentRepository commitments;
    @Autowired ContractAttestationRepository attestations;
    @Autowired Clock clock;

    @MockitoBean JwtDecoder jwtDecoder;

    private final List<McpSyncClient> openClients = new ArrayList<>();
    private Contract coTribal;

    @BeforeEach
    void setUp() {
        when(jwtDecoder.decode(AGENT_TOKEN)).thenReturn(Jwt.withTokenValue(AGENT_TOKEN)
                .header("alg", "none")
                .subject(AGENT_SUB)
                .claim("azp", "substrate-agent")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(Duration.ofHours(1)))
                .build());
        coTribal = contracts.findByContractKey(CO_TRIBAL).get(0);
    }

    @AfterEach
    void tearDown() {
        openClients.forEach(McpSyncClient::close);
        openClients.clear();
    }

    // ------------------------------------------------- unauthenticated → 401

    @Test
    void unauthenticatedMcpIs401EvenWithASpoofedPrincipalHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Accept", "application/json, text/event-stream");
        headers.set(ContractsMcpConfig.PRINCIPAL_HEADER, "spoofed-agent");
        ResponseEntity<String> response = rest.postForEntity("/mcp",
                new HttpEntity<>("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", headers),
                String.class);
        assertThat(response.getStatusCode().value())
                .as("oidc mode: header trust is dead — no token, no MCP (D8)")
                .isEqualTo(401);
    }

    // --------------------------------------- principal = verified token sub

    @Test
    void governedWriteRecordsTheTokenSubNotTheSpoofedHeader() {
        ContractCommitment monthly = commitments
                .findByContractIdOrderBySequenceOrderAsc(coTribal.getId()).stream()
                .filter(c -> "MONTHLY".equals(c.getCadence()))
                .findFirst().orElseThrow();
        String period = PeriodKeys.previousPeriodKey("MONTHLY", clock.instant(), ZoneOffset.UTC);

        McpSchema.CallToolResult result = bearerClient().callTool(
                new McpSchema.CallToolRequest("attest_commitment", Map.of(
                        "commitment_id", monthly.getId().toString(),
                        "period_key", period,
                        "compliance_claim", "ON_TRACK",
                        "statement", "attested under oidc client credentials")));
        assertThat(result.isError())
                .as("attest_commitment (content: %s)", result.content())
                .isNotEqualTo(Boolean.TRUE);

        var row = attestations.findByCommitmentIdAndPeriodKey(monthly.getId(), period).orElseThrow();
        assertThat(row.getAttestedBy())
                .as("actor identity = verified token sub; spoofed X-MCP-Principal ignored")
                .isEqualTo(AGENT_SUB);
    }

    @Test
    void partyBindingResolvesAgainstTheTokenSub() {
        ContractParty party = parties
                .findByContractIdOrderBySequenceOrderAsc(coTribal.getId()).get(0);
        if (!AGENT_SUB.equals(party.getMcpPrincipalId())) {
            party.setMcpPrincipalId(AGENT_SUB);
            parties.save(party);
        }

        McpSchema.CallToolResult result = bearerClient().callTool(
                new McpSchema.CallToolRequest("report_metric", Map.of(
                        "contract_key", CO_TRIBAL,
                        "metric_key", "utilization_share",
                        "value", 0.5,
                        "period_key", "2026-Q1")));
        assertThat(result.isError())
                .as("party resolved through contract_parties.mcp_principal_id = token sub"
                        + " (content: %s)", result.content())
                .isNotEqualTo(Boolean.TRUE);
        JsonNode structured = objectMapper.valueToTree(result.structuredContent());
        assertThat(structured.get("reportedBy").asText()).isEqualTo(AGENT_SUB);
    }

    // --------------------------------------------------------------- plumbing

    /** Streamable-HTTP client sending Bearer + a spoofed header (must be inert). */
    private McpSyncClient bearerClient() {
        var transport = HttpClientStreamableHttpTransport
                .builder("http://localhost:" + port)
                .endpoint(ContractsMcpConfig.MCP_ENDPOINT)
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .httpRequestCustomizer((builder, method, uri, body, context) -> {
                    builder.header("Authorization", "Bearer " + AGENT_TOKEN);
                    builder.header(ContractsMcpConfig.PRINCIPAL_HEADER, "spoofed-other-agent");
                })
                .build();
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("oidc-mcp-suite", "0.1.0"))
                .requestTimeout(Duration.ofSeconds(30))
                .build();
        client.initialize();
        openClients.add(client);
        return client;
    }
}
