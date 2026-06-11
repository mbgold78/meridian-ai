package com.meridian.substrate.app.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractCommitment;
import com.meridian.substrate.common.domain.ContractMonitor;
import com.meridian.substrate.common.domain.ContractParty;
import com.meridian.substrate.common.domain.enums.ComplianceClaim;
import com.meridian.substrate.common.domain.enums.ContractPartyType;
import com.meridian.substrate.common.domain.enums.ContractStatus;
import com.meridian.substrate.common.domain.enums.GatewayDecision;
import com.meridian.substrate.common.domain.enums.GuardEffect;
import com.meridian.substrate.common.domain.enums.PartyResponse;
import com.meridian.substrate.common.repo.ContractAttestationRepository;
import com.meridian.substrate.common.repo.ContractCommitmentRepository;
import com.meridian.substrate.common.repo.ContractEnforcementDecisionRepository;
import com.meridian.substrate.common.repo.ContractEventRepository;
import com.meridian.substrate.common.repo.ContractMonitorRepository;
import com.meridian.substrate.common.repo.ContractPartyRepository;
import com.meridian.substrate.common.repo.ContractRepository;
import com.meridian.substrate.engine.contract.ContractProperties;
import com.meridian.substrate.engine.contract.gateway.GovernedActions;
import com.meridian.substrate.engine.contract.monitor.PeriodKeys;
import com.meridian.substrate.engine.contract.registry.ContractActor;
import com.meridian.substrate.engine.contract.registry.ContractEventService;
import com.meridian.substrate.engine.contract.registry.ContractRegistryService;
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
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §14.5 MCP suite, WRITE half (engine-spec contracts.md §8.3/§14.5; ADR-002
 * D2/D16): scripted Streamable-HTTP client against the governed write tools —
 * attest_commitment idempotency; report_metric flipping a METRIC_THRESHOLD
 * monitor INDETERMINATE → COMPLIANT; invoke_decline returning a SUCCESS shape
 * (NOT isError — decline is legitimate); waive_breach as AGENT ⇒ structured
 * denial; check_action verdict parity with a subsequent real write in enforce
 * mode (§4.5 denial shape over MCP and 409 CONTRACT_DENIAL over REST).
 *
 * <p>Fixture data = DemoPack §14.1 vectors (source of truth
 * {@code C:/Users/mbgol/dev/meridian-homepoc/frontend/src/demo/beacon/contracts.ts},
 * copied into ContractSeeder 2026-06-10); guard-bearing contracts are
 * deliberately SYNTHETIC, created in-test through the real registry path
 * (spec §16 — fixture contracts compile to zero guards). Principal↔party
 * bindings ({@code contract_parties.mcp_principal_id}) are test-arranged —
 * pre-Phase-5 identity is the documented-spoofable header (spec §15 #12).
 *
 * <p>Enforcement-mode flips use the mutable {@code ContractProperties} bean
 * (read at evaluate time, ADR D4 — the {@code PostgresContractGatewayDenyRollbackIT}
 * idiom), restored to shadow after each test.
 *
 * <p>H2 fast lane; the Testcontainers-Postgres suite in CI is the acceptance
 * gate — H2-green is never acceptance (port-map §4.4).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "substrate.demo.seed=true",
        "substrate.demo.contracts.activate=true",
        // keep the background sweep out of the test window
        "substrate.contracts.monitor.sweep-interval=PT1H",
        "substrate.contracts.enforcement.mode=shadow",
        "spring.datasource.url=jdbc:h2:mem:mcp_write;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("dev")
class ContractsMcpWriteTest {

    static final String CO_TRIBAL = "co-tribal-knowledge-depopulation";
    static final String FI_SHARED = "fi-shared-pre-fill-agent";
    static final String AGENT_PRINCIPAL = "write-suite-agent";

    @LocalServerPort
    int port;
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper objectMapper;
    @Autowired ContractRepository contracts;
    @Autowired ContractPartyRepository parties;
    @Autowired ContractCommitmentRepository commitments;
    @Autowired ContractMonitorRepository monitors;
    @Autowired ContractAttestationRepository attestations;
    @Autowired ContractEventRepository events;
    @Autowired ContractEnforcementDecisionRepository decisions;
    @Autowired ContractRegistryService registryService;
    @Autowired ContractProperties properties;
    @Autowired Clock clock;

    private final List<McpSyncClient> openClients = new ArrayList<>();
    private Contract coTribal;

    @BeforeEach
    void setUp() {
        coTribal = contracts.findByContractKey(CO_TRIBAL).get(0);
        assertThat(coTribal.getStatus()).isEqualTo(ContractStatus.ACTIVE);
    }

    @AfterEach
    void tearDown() {
        properties.getEnforcement().setMode("shadow");   // ADR D4 ship posture restored
        openClients.forEach(McpSyncClient::close);
        openClients.clear();
    }

    // ------------------------------------------------------------ inventory

    @Test
    void serverExposesTheSpec83WriteToolInventory() {
        var tools = client(AGENT_PRINCIPAL).listTools().tools().stream()
                .map(McpSchema.Tool::name).toList();
        assertThat(tools).contains(
                "attest_commitment", "record_commitment_progress", "report_metric",
                "respond_to_contract", "invoke_decline",
                "acknowledge_breach", "mark_breach_remediated", "waive_breach");
    }

    @Test
    void writeToolsWithoutPrincipalHeaderAreStructuredErrors() {
        McpSchema.CallToolResult result = client(null).callTool(
                new McpSchema.CallToolRequest("attest_commitment", Map.of(
                        "commitment_id", UUID.randomUUID().toString(),
                        "period_key", "2026-05",
                        "compliance_claim", "ON_TRACK")));
        assertThat(result.isError()).isTrue();
        assertThat(structured(result).get("code").asText()).isEqualTo("PRINCIPAL_REQUIRED");
    }

    // ------------------------------------------- attest_commitment idempotency

    @Test
    void attestCommitmentIsAnIdempotentUpsertWithDurableEventHistory() {
        ContractCommitment monthly = monthlyReportCommitment();
        String period = PeriodKeys.previousPeriodKey("MONTHLY", clock.instant(), ZoneOffset.UTC);
        McpSyncClient agent = client(AGENT_PRINCIPAL);

        JsonNode first = callOk(agent, "attest_commitment", Map.of(
                "commitment_id", monthly.getId().toString(),
                "period_key", period,
                "compliance_claim", "ON_TRACK",
                "statement", "first submission"));
        JsonNode second = callOk(agent, "attest_commitment", Map.of(
                "commitment_id", monthly.getId().toString(),
                "period_key", period,
                "compliance_claim", "COMPLETE",
                "statement", "re-submission supersedes"));

        // §5.5/ADR D13: ONE row (latest wins) …
        assertThat(first.get("id").asText()).isEqualTo(second.get("id").asText());
        var row = attestations.findByCommitmentIdAndPeriodKey(monthly.getId(), period).orElseThrow();
        assertThat(row.getComplianceClaim()).isEqualTo(ComplianceClaim.COMPLETE);
        assertThat(row.getSubmittedVia()).isEqualTo("MCP");
        assertThat(row.getAttestedBy()).isEqualTo(AGENT_PRINCIPAL);

        // … and EVERY submission appended its event (history durable).
        long attestedEvents = events.findByContractIdOrderByOccurredAtAsc(coTribal.getId()).stream()
                .filter(e -> ContractEventService.COMMITMENT_ATTESTED.equals(e.getEventType()))
                .filter(e -> monthly.getId().equals(e.getRefId()))
                .filter(e -> e.getPayload() != null
                        && period.equals(e.getPayload().path("periodKey").asText()))
                .count();
        assertThat(attestedEvents).isEqualTo(2);
    }

    @Test
    void recordCommitmentProgressAppendsTheProgressEventOnly() {
        ContractCommitment monthly = monthlyReportCommitment();
        JsonNode event = callOk(client(AGENT_PRINCIPAL), "record_commitment_progress", Map.of(
                "commitment_id", monthly.getId().toString(),
                "note", "template draft circulated to subsidiary COOs"));
        assertThat(event.get("eventType").asText())
                .isEqualTo(ContractEventService.COMMITMENT_PROGRESS);
        assertThat(event.get("actorType").asText()).isEqualTo("AGENT");
        assertThat(event.get("actorId").asText()).isEqualTo(AGENT_PRINCIPAL);
    }

    // ------------------------- report_metric → METRIC_THRESHOLD flip (§14.5)

    @Test
    void reportMetricFlipsTheMetricThresholdMonitorFromIndeterminateToCompliant() {
        Contract fiShared = contracts.findByContractKey(FI_SHARED).get(0);
        List<ContractParty> fiParties =
                parties.findByContractIdOrderBySequenceOrderAsc(fiShared.getId());
        assertThat(fiParties).hasSize(2);
        bindPrincipal(fiParties.get(0), "polestar-agent");
        bindPrincipal(fiParties.get(1), "curasync-agent");
        activate(fiShared);

        ContractMonitor monitor = monitors.findByContractId(fiShared.getId()).stream()
                .filter(m -> "utilization-imbalance".equals(m.getName()))
                .findFirst().orElseThrow();

        // Zero samples seeded (§14.1 pin) ⇒ INDETERMINATE, never a breach (ADR D11).
        assertThat(runNow(FI_SHARED, monitor.getId())).isEqualTo("INDETERMINATE");

        // Both parties report through the governed MCP tool (party resolved
        // from each caller's principal binding, §5.5).
        String period = PeriodKeys.previousPeriodKey("QUARTERLY", clock.instant(), ZoneOffset.UTC);
        callOk(client("polestar-agent"), "report_metric", Map.of(
                "contract_key", FI_SHARED, "metric_key", "utilization_share",
                "value", 0.52, "period_key", period));
        callOk(client("curasync-agent"), "report_metric", Map.of(
                "contract_key", FI_SHARED, "metric_key", "utilization_share",
                "value", 0.48, "period_key", period));

        // |0.52 - 0.48| = 0.04 <= 0.30 ⇒ COMPLIANT (the §14.5 flip).
        assertThat(runNow(FI_SHARED, monitor.getId())).isEqualTo("COMPLIANT");
    }

    @Test
    void reportMetricWithoutAPartyBindingIsAStructuredError() {
        McpSchema.CallToolResult result = client("unbound-agent").callTool(
                new McpSchema.CallToolRequest("report_metric", Map.of(
                        "contract_key", CO_TRIBAL, "metric_key", "utilization_share",
                        "value", 0.5, "period_key", "2026-Q1")));
        assertThat(result.isError()).isTrue();
        JsonNode structured = structured(result);
        assertThat(structured.get("code").asText()).isEqualTo("INVALID_ARGUMENT");
        assertThat(structured.get("message").asText()).contains("mcp_principal_id");
    }

    // ------------------------------------------ invoke_decline success shape

    @Test
    void invokeDeclineIsASuccessShapeNeverIsError() {
        Contract synthetic = createTwoPartyContract("mcp-decline-target", List.of());
        activate(synthetic);
        UUID triggerId = firstTriggerId(synthetic);

        McpSchema.CallToolResult result = client(AGENT_PRINCIPAL).callTool(
                new McpSchema.CallToolRequest("invoke_decline", Map.of(
                        "contract_key", "mcp-decline-target",
                        "decline_trigger_id", triggerId.toString(),
                        "reasoning", "pre-agreed ground invoked by the party agent")));

        assertThat(result.isError())
                .as("§8.3: invoke_decline is a first-class legitimate terminal action —"
                        + " success result, never isError (port-map trap #8)")
                .isNotEqualTo(Boolean.TRUE);
        JsonNode detail = structured(result);
        assertThat(detail.get("status").asText()).isEqualTo("DECLINED");
        assertThat(detail.get("resolvedAt").isNull()).isFalse();
        assertThat(contracts.findByContractKey("mcp-decline-target").get(0).getStatus())
                .isEqualTo(ContractStatus.DECLINED);
    }

    // --------------------------------------------- waive_breach AGENT denial

    @Test
    void waiveBreachAsAgentIsDeniedInShadowByTheBackstopWithAWouldDenyRow() {
        UUID breachId = openBreachVia("mcp-waive-shadow");
        Instant since = clock.instant().minusSeconds(1);

        McpSchema.CallToolResult result = client(AGENT_PRINCIPAL).callTool(
                new McpSchema.CallToolRequest("waive_breach", Map.of(
                        "breach_id", breachId.toString(),
                        "rationale", "agent attempting a waiver")));

        // Shadow never blocks at the gateway (ADR D4) — the mode-independent
        // WaiverHumanOnlyException backstop denies (ADR D16)…
        assertThat(result.isError()).isTrue();
        assertThat(structured(result).get("code").asText()).isEqualTo("WAIVER_HUMAN_ONLY");

        // …while the gateway's built-in guard rowed WOULD_DENY for the audit trail.
        var rows = decisions.findByDomainIdAndDecidedAtAfter(coTribal.getDomainId(), since).stream()
                .filter(d -> GovernedActions.CONTRACT_WAIVE_BREACH.equals(d.getActionType()))
                .toList();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getDecision()).isEqualTo(GatewayDecision.WOULD_DENY);
        assertThat(rows.get(0).getReason()).isEqualTo("WAIVER_HUMAN_ONLY");
        assertThat(rows.get(0).getActorId()).isEqualTo(AGENT_PRINCIPAL);
    }

    @Test
    void waiveBreachAsAgentIsAContractDenialInEnforceMode() {
        UUID breachId = openBreachVia("mcp-waive-enforce");
        properties.getEnforcement().setMode("enforce");

        McpSchema.CallToolResult result = client(AGENT_PRINCIPAL).callTool(
                new McpSchema.CallToolRequest("waive_breach", Map.of(
                        "breach_id", breachId.toString(),
                        "rationale", "agent attempting a waiver")));

        assertThat(result.isError()).isTrue();
        JsonNode denial = structured(result);
        assertThat(denial.get("code").asText()).isEqualTo("CONTRACT_DENIAL");
        assertThat(denial.get("reason").asText()).isEqualTo("WAIVER_HUMAN_ONLY");
        assertThat(denial.get("decisionId").asText()).isNotBlank();
        assertThat(denial.get("message").asText()).contains("human-only");
    }

    // ------------- check_action parity + §4.5 denial shapes in enforce mode

    @Test
    void checkActionVerdictMatchesTheSubsequentRealWriteInEnforceMode() {
        // Synthetic guard-bearing contract (spec §16): DENY on the REAL
        // governed verb contract.report_metric, on BOTH transports.
        Contract guarded = createTwoPartyContract("mcp-guarded-metrics", List.of(
                new ContractRegistryService.GuardCommand(
                        GovernedActions.CONTRACT_REPORT_METRIC, null, GuardEffect.DENY, null,
                        "Metric reporting is blocked under this synthetic contract")));
        List<ContractParty> guardedParties =
                parties.findByContractIdOrderBySequenceOrderAsc(guarded.getId());
        bindPrincipal(guardedParties.get(0), "guarded-agent");
        activate(guarded);

        // §8.2/§13: the advisory dry-run reports the would-be enforce outcome.
        JsonNode verdict = callOk(client("guarded-agent"), "check_action",
                Map.of("action_type", GovernedActions.CONTRACT_REPORT_METRIC));
        assertThat(verdict.get("verdict").asText()).isEqualTo("BLOCK");

        properties.getEnforcement().setMode("enforce");

        // MCP lane: §4.5 structured isError payload, never a protocol error.
        McpSchema.CallToolResult result = client("guarded-agent").callTool(
                new McpSchema.CallToolRequest("report_metric", Map.of(
                        "contract_key", "mcp-guarded-metrics",
                        "metric_key", "utilization_share",
                        "value", 0.9, "period_key", "2026-Q1")));
        assertThat(result.isError()).isTrue();
        JsonNode denial = structured(result);
        assertThat(denial.get("code").asText()).isEqualTo("CONTRACT_DENIAL");
        assertThat(denial.get("contractKeys")).extracting(JsonNode::asText)
                .containsExactly("mcp-guarded-metrics");
        assertThat(denial.get("clauseIds")).hasSize(1);
        assertThat(denial.get("decisionId").asText()).isNotBlank();

        // REST lane, same action type: 409 envelope code CONTRACT_DENIAL (§4.5).
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.postForEntity(
                "/api/v1/contracts/mcp-guarded-metrics/metric-samples",
                new HttpEntity<>(Map.of(
                        "partyId", guardedParties.get(0).getId().toString(),
                        "metricKey", "utilization_share",
                        "value", 0.9,
                        "periodKey", "2026-Q1"), headers),
                String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        JsonNode envelope = readTree(response.getBody());
        assertThat(envelope.get("errors").get(0).get("code").asText())
                .isEqualTo("CONTRACT_DENIAL");
        assertThat(envelope.get("errors").get(0).get("message").asText())
                .contains("mcp-guarded-metrics");
    }

    // ----------------------------------------------------------- test support

    private ContractCommitment monthlyReportCommitment() {
        return commitments.findByContractIdOrderBySequenceOrderAsc(coTribal.getId()).stream()
                .filter(c -> "MONTHLY".equals(c.getCadence()))
                .findFirst().orElseThrow();
    }

    private void bindPrincipal(ContractParty party, String principal) {
        if (!principal.equals(party.getMcpPrincipalId())) {
            party.setMcpPrincipalId(principal);
            parties.save(party);
        }
    }

    /** Activates through the real respond path (idempotent re-entry, §10.2). */
    private void activate(Contract contract) {
        for (ContractParty party
                : parties.findByContractIdOrderBySequenceOrderAsc(contract.getId())) {
            if (party.isResponseRequired()) {
                registryService.respond(contract.getId(),
                        new ContractRegistryService.RespondCommand(
                                party.getId(), PartyResponse.ACCEPTED, null, null,
                                "write-suite", ContractActor.human("write-suite")));
            }
        }
        assertThat(contracts.findById(contract.getId()).orElseThrow().getStatus())
                .isEqualTo(ContractStatus.ACTIVE);
    }

    /** Synthetic two-party contract via the real registry path (idempotent per key). */
    private Contract createTwoPartyContract(String key,
                                            List<ContractRegistryService.GuardCommand> guards) {
        return contracts.findByDomainIdAndContractKey(coTribal.getDomainId(), key)
                .orElseGet(() -> registryService.create(
                        new ContractRegistryService.CreateContractCommand(
                                coTribal.getDomainId(), key, "cross-portfolio-opportunity",
                                "Write-suite synthetic " + key, null, null, null, null, null, null,
                                clock.instant(), "write-suite",
                                List.of(twoPartyCommand("Party A " + key),
                                        twoPartyCommand("Party B " + key)),
                                List.of(new ContractRegistryService.DeclineTriggerCommand(
                                        "Pre-agreed synthetic ground", null, null)),
                                List.of(), guards, List.of())));
    }

    private static ContractRegistryService.PartyCommand twoPartyCommand(String label) {
        return new ContractRegistryService.PartyCommand(label, ContractPartyType.ORG_UNIT,
                null, true, List.of(new ContractRegistryService.CommitmentCommand(
                        "Synthetic commitment of " + label, null, null)));
    }

    private UUID firstTriggerId(Contract contract) {
        JsonNode detail = readTree(rest.getForEntity(
                "/api/v1/contracts/" + contract.getContractKey(), String.class).getBody());
        return UUID.fromString(detail.get("data").get("enforcement")
                .get("declineTriggers").get(0).get("id").asText());
    }

    /**
     * Opens a real breach on a fresh synthetic ACTIVE contract by attesting
     * MISSED over MCP (§5.3 — auto-opens, detected_by=HUMAN_ATTESTATION).
     */
    private UUID openBreachVia(String contractKey) {
        Contract contract = createTwoPartyContract(contractKey, List.of());
        activate(contract);
        ContractCommitment commitment = commitments
                .findByContractIdOrderBySequenceOrderAsc(contract.getId()).get(0);
        callOk(client(AGENT_PRINCIPAL), "attest_commitment", Map.of(
                "commitment_id", commitment.getId().toString(),
                "period_key", PeriodKeys.ONE_TIME,
                "compliance_claim", "MISSED",
                "statement", "missed — opens the breach for the waive lane"));
        JsonNode breaches = readTree(rest.getForEntity(
                "/api/v1/contracts/" + contractKey + "/breaches", String.class).getBody());
        assertThat(breaches.get("data")).hasSize(1);
        return UUID.fromString(breaches.get("data").get(0).get("id").asText());
    }

    /** REST run-now (the §5.2 operator lever); returns the run outcome. */
    private String runNow(String contractKey, UUID monitorId) {
        ResponseEntity<String> response = rest.postForEntity(
                "/api/v1/contracts/" + contractKey + "/monitors/" + monitorId + "/run-now",
                null, String.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("run-now %s -> %s", contractKey, response.getStatusCode()).isTrue();
        return readTree(response.getBody()).get("data").get("outcome").asText();
    }

    // ------------------------------------------------------------- plumbing

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
                .clientInfo(new McpSchema.Implementation("contracts-write-suite", "0.1.0"))
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

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("unparseable JSON: " + json, e);
        }
    }
}
