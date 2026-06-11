package com.meridian.substrate.app.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractParty;
import com.meridian.substrate.common.domain.enums.ComplianceClaim;
import com.meridian.substrate.common.domain.enums.PartyResponse;
import com.meridian.substrate.common.repo.ContractPartyRepository;
import com.meridian.substrate.engine.contract.exception.ContractConflictException;
import com.meridian.substrate.engine.contract.exception.WaiverHumanOnlyException;
import com.meridian.substrate.engine.contract.gateway.ContractDeniedException;
import com.meridian.substrate.engine.contract.gateway.Decision;
import com.meridian.substrate.engine.contract.gateway.GovernedPrincipalScope;
import com.meridian.substrate.engine.contract.gateway.Principal;
import com.meridian.substrate.engine.contract.monitor.AttestationService;
import com.meridian.substrate.engine.contract.monitor.BreachService;
import com.meridian.substrate.engine.contract.monitor.MetricSampleService;
import com.meridian.substrate.engine.contract.read.AttestationResponse;
import com.meridian.substrate.engine.contract.read.BreachResponse;
import com.meridian.substrate.engine.contract.read.ContractEventResponse;
import com.meridian.substrate.engine.contract.read.MetricSampleResponse;
import com.meridian.substrate.engine.contract.registry.ContractActor;
import com.meridian.substrate.engine.contract.registry.ContractReadService;
import com.meridian.substrate.engine.contract.registry.ContractRegistryService;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * The §8.3 GOVERNED write tools of the {@code substrate-contracts} MCP server
 * (engine-spec contracts.md §8.3/§4.2 PEP 1; ADR-002 D2/D16) — thin app-side
 * adapters over the SAME engine services the REST surface uses. Every call
 * transits the {@code ContractGateway}: the adapter wraps the service call in
 * {@link GovernedPrincipalScope} with the AGENT principal lifted from the
 * {@code X-MCP-Principal} transport context, and the {@code @Governed} aspect
 * on the service method evaluates the verb's action type (single evaluation
 * per call — REST and MCP share both the service method and the action
 * vocabulary, so guards govern actions, not transports).
 *
 * <p><b>Denial wire shape (§4.5):</b> an enforce-mode DENY surfaces as an
 * {@code isError=true} TOOL RESULT with structured content
 * {@code {code: CONTRACT_DENIAL, contractKeys, clauseIds, reason, decisionId,
 * message}} — never an MCP protocol error, so agents adapt rather than crash.
 * Conflict-family outcomes map the same way ({@code CONTRACT_CONTENT_FROZEN},
 * {@code INVALID_CONTRACT_STATE}, {@code RESPONSE_CONFLICT},
 * {@code WAIVER_HUMAN_ONLY}).
 *
 * <p><b>DECLINE is never an error:</b> {@code invoke_decline} and a DECLINED
 * outcome of {@code respond_to_contract} return SUCCESS results carrying the
 * full detail (port-map trap #8; ADR D6) — the only error-shaped decline
 * responses are state conflicts (§2.2), exactly like REST.
 *
 * <p><b>Identity:</b> write tools REQUIRE the principal header
 * ({@code PRINCIPAL_REQUIRED} otherwise) — a write without an actor identity
 * would corrupt the §2.1 actor audit trail. Phase 1-4 localhost trust,
 * documented spoofable (spec §15 #12). {@code report_metric} and
 * {@code invoke_decline} resolve the caller's party from
 * {@code contract_parties.mcp_principal_id} (§5.5 party authority — advisory
 * pre-Phase-5).
 *
 * <p>No {@code waive_breach} special-casing here: the ADR D16 built-in
 * non-removable guard lives in the gateway ({@code contract.waive_breach}
 * denied for AGENT principals) with the mode-independent
 * {@link WaiverHumanOnlyException} service backstop — this adapter only
 * translates the outcomes onto the wire.
 */
public class ContractsMcpWriteTools {

    private static final Logger log = LoggerFactory.getLogger(ContractsMcpWriteTools.class);
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE =
            new TypeReference<>() {
            };

    /** §8.3 — how attestations submitted over this surface are stamped (§3.2 table 10). */
    static final String SUBMITTED_VIA_MCP = "MCP";

    private final ContractReadService readService;
    private final ContractRegistryService registryService;
    private final AttestationService attestationService;
    private final MetricSampleService metricSampleService;
    private final BreachService breachService;
    private final ContractPartyRepository partyRepository;
    private final ObjectMapper objectMapper;
    private final McpJsonMapper mcpJsonMapper;

    public ContractsMcpWriteTools(ContractReadService readService,
                                  ContractRegistryService registryService,
                                  AttestationService attestationService,
                                  MetricSampleService metricSampleService,
                                  BreachService breachService,
                                  ContractPartyRepository partyRepository,
                                  ObjectMapper objectMapper,
                                  McpJsonMapper mcpJsonMapper) {
        this.readService = readService;
        this.registryService = registryService;
        this.attestationService = attestationService;
        this.metricSampleService = metricSampleService;
        this.breachService = breachService;
        this.partyRepository = partyRepository;
        this.objectMapper = objectMapper;
        this.mcpJsonMapper = mcpJsonMapper;
    }

    /** The §8.3 write-tool inventory, in spec order. */
    public List<McpServerFeatures.SyncToolSpecification> specifications() {
        return List.of(
                spec(tool("attest_commitment",
                        "GOVERNED: submit a compliance attestation for a commitment period"
                                + " (§5.5 idempotent upsert on (commitment_id, period_key);"
                                + " every submission appends a COMMITMENT_ATTESTED event)."
                                + " complianceClaim MISSED auto-opens a breach.",
                        """
                        {"type":"object","properties":{
                          "commitment_id":{"type":"string","description":"Commitment UUID"},
                          "period_key":{"type":"string","description":"e.g. 2026-06 / 2026-Q2 / ONE_TIME"},
                          "compliance_claim":{"type":"string","description":"ON_TRACK | COMPLETE | AT_RISK | MISSED"},
                          "statement":{"type":"string"},
                          "evidence_refs":{"type":"array","items":{"type":"string"}}},
                         "required":["commitment_id","period_key","compliance_claim"],
                         "additionalProperties":false}"""),
                        this::attestCommitment),
                spec(tool("record_commitment_progress",
                        "GOVERNED: append a COMMITMENT_PROGRESS note to the contract timeline"
                                + " (event only — no compliance projection).",
                        """
                        {"type":"object","properties":{
                          "commitment_id":{"type":"string","description":"Commitment UUID"},
                          "note":{"type":"string"},
                          "evidence_refs":{"type":"array","items":{"type":"string"}}},
                         "required":["commitment_id","note"],"additionalProperties":false}"""),
                        this::recordCommitmentProgress),
                spec(tool("report_metric",
                        "GOVERNED: push one metric sample (§5.5 idempotent upsert; feeds"
                                + " METRIC_THRESHOLD monitors and decline-trigger arming, ADR"
                                + " D11). The reporting party is resolved from the caller's"
                                + " principal binding (contract_parties.mcp_principal_id).",
                        """
                        {"type":"object","properties":{
                          "contract_key":{"type":"string"},
                          "metric_key":{"type":"string","description":"e.g. utilization_share, accuracy"},
                          "value":{"type":"number"},
                          "period_key":{"type":"string","description":"e.g. 2026-Q1"}},
                         "required":["contract_key","metric_key","value","period_key"],
                         "additionalProperties":false}"""),
                        this::reportMetric),
                spec(tool("respond_to_contract",
                        "GOVERNED: record a party's immutable proposal response (§2.2). The"
                                + " last required ACCEPTED activates; a required DECLINED"
                                + " resolves the contract DECLINED — a SUCCESS result (decline"
                                + " is a legitimate outcome, never an error).",
                        """
                        {"type":"object","properties":{
                          "contract_key":{"type":"string"},
                          "party_id":{"type":"string","description":"ContractParty UUID"},
                          "response":{"type":"string","description":"ACCEPTED | DECLINED"},
                          "decline_trigger_id":{"type":"string"},
                          "decline_reasoning":{"type":"string","description":"required iff response=DECLINED"}},
                         "required":["contract_key","party_id","response"],
                         "additionalProperties":false}"""),
                        this::respondToContract),
                spec(tool("invoke_decline",
                        "GOVERNED: invoke a pre-agreed decline trigger on an ACTIVE contract"
                                + " (§6.1) — a first-class legitimate terminal action; returns a"
                                + " SUCCESS result with the DECLINED detail, never isError."
                                + " Reasoning is mandatory.",
                        """
                        {"type":"object","properties":{
                          "contract_key":{"type":"string"},
                          "decline_trigger_id":{"type":"string"},
                          "reasoning":{"type":"string"}},
                         "required":["contract_key","decline_trigger_id","reasoning"],
                         "additionalProperties":false}"""),
                        this::invokeDecline),
                spec(tool("acknowledge_breach",
                        "GOVERNED: acknowledge a SURFACED breach (§2.4 — agents MAY"
                                + " acknowledge, ADR D16).",
                        """
                        {"type":"object","properties":{
                          "breach_id":{"type":"string"},
                          "note":{"type":"string"}},
                         "required":["breach_id"],"additionalProperties":false}"""),
                        this::acknowledgeBreach),
                spec(tool("mark_breach_remediated",
                        "GOVERNED: mark an ACKNOWLEDGED breach REMEDIATED with mandatory"
                                + " evidence refs (§2.4); the commitment returns to COMPLIANT.",
                        """
                        {"type":"object","properties":{
                          "breach_id":{"type":"string"},
                          "evidence_refs":{"type":"array","items":{"type":"string"}}},
                         "required":["breach_id","evidence_refs"],"additionalProperties":false}"""),
                        this::markBreachRemediated),
                spec(tool("waive_breach",
                        "GOVERNED: waive a breach with mandatory rationale. HUMAN-ONLY by the"
                                + " ADR D16 built-in non-removable guard — AGENT principals are"
                                + " ALWAYS denied (structured isError, code CONTRACT_DENIAL in"
                                + " enforce mode / WAIVER_HUMAN_ONLY backstop otherwise).",
                        """
                        {"type":"object","properties":{
                          "breach_id":{"type":"string"},
                          "rationale":{"type":"string"}},
                         "required":["breach_id","rationale"],"additionalProperties":false}"""),
                        this::waiveBreach));
    }

    // ------------------------------------------------------------- handlers

    private McpSchema.CallToolResult attestCommitment(McpSyncServerExchange exchange,
                                                      Map<String, Object> args) {
        Principal principal = requirePrincipal(exchange);
        UUID commitmentId = requiredUuid(args, "commitment_id");
        String periodKey = requiredArg(args, "period_key");
        ComplianceClaim claim = requiredEnum(ComplianceClaim.class, args, "compliance_claim");
        JsonNode evidenceRefs = nodeArg(args, "evidence_refs");
        return GovernedPrincipalScope.callAs(principal, () -> ok(AttestationResponse.from(
                attestationService.attest(new AttestationService.AttestCommand(
                        commitmentId, periodKey, claim, stringArg(args, "statement"),
                        evidenceRefs, principal.id(), SUBMITTED_VIA_MCP,
                        ContractActor.agent(principal.id()))))));
    }

    private McpSchema.CallToolResult recordCommitmentProgress(McpSyncServerExchange exchange,
                                                              Map<String, Object> args) {
        Principal principal = requirePrincipal(exchange);
        UUID commitmentId = requiredUuid(args, "commitment_id");
        String note = requiredArg(args, "note");
        JsonNode evidenceRefs = nodeArg(args, "evidence_refs");
        return GovernedPrincipalScope.callAs(principal, () -> ok(ContractEventResponse.from(
                attestationService.recordProgress(commitmentId, note, evidenceRefs,
                        ContractActor.agent(principal.id())))));
    }

    private McpSchema.CallToolResult reportMetric(McpSyncServerExchange exchange,
                                                  Map<String, Object> args) {
        Principal principal = requirePrincipal(exchange);
        Contract contract = requiredContract(args);
        ContractParty party = boundParty(contract, principal);
        double value = requiredNumber(args, "value");
        return GovernedPrincipalScope.callAs(principal, () -> ok(MetricSampleResponse.from(
                metricSampleService.report(new MetricSampleService.ReportMetricCommand(
                        contract.getId(), party.getId(), requiredArg(args, "metric_key"),
                        value, requiredArg(args, "period_key"), principal.id(),
                        ContractActor.agent(principal.id()))))));
    }

    private McpSchema.CallToolResult respondToContract(McpSyncServerExchange exchange,
                                                       Map<String, Object> args) {
        Principal principal = requirePrincipal(exchange);
        Contract contract = requiredContract(args);
        PartyResponse response = requiredEnum(PartyResponse.class, args, "response");
        UUID partyId = requiredUuid(args, "party_id");
        UUID declineTriggerId = optionalUuid(args, "decline_trigger_id");
        return GovernedPrincipalScope.callAs(principal, () -> {
            registryService.respond(contract.getId(), new ContractRegistryService.RespondCommand(
                    partyId, response, declineTriggerId, stringArg(args, "decline_reasoning"),
                    principal.id(), ContractActor.agent(principal.id())));
            // SUCCESS result either way — a DECLINED resolution is legitimate (§2.2).
            return ok(readService.detail(reload(contract)));
        });
    }

    private McpSchema.CallToolResult invokeDecline(McpSyncServerExchange exchange,
                                                   Map<String, Object> args) {
        Principal principal = requirePrincipal(exchange);
        Contract contract = requiredContract(args);
        UUID triggerId = requiredUuid(args, "decline_trigger_id");
        String reasoning = requiredArg(args, "reasoning");
        // §6.1 invoker-party check: the caller's bound party when one exists
        // (pre-Phase-5 party authority is advisory, spec §15 #12).
        UUID invokingPartyId = optionalBoundParty(contract, principal);
        return GovernedPrincipalScope.callAs(principal, () -> {
            registryService.invokeDecline(contract.getId(),
                    new ContractRegistryService.InvokeDeclineCommand(
                            triggerId, reasoning, invokingPartyId,
                            ContractActor.agent(principal.id())));
            // First-class legitimate terminal action: SUCCESS shape, never isError.
            return ok(readService.detail(reload(contract)));
        });
    }

    private McpSchema.CallToolResult acknowledgeBreach(McpSyncServerExchange exchange,
                                                       Map<String, Object> args) {
        Principal principal = requirePrincipal(exchange);
        UUID breachId = requiredUuid(args, "breach_id");
        return GovernedPrincipalScope.callAs(principal, () -> ok(BreachResponse.from(
                breachService.acknowledge(breachId, stringArg(args, "note"),
                        ContractActor.agent(principal.id())))));
    }

    private McpSchema.CallToolResult markBreachRemediated(McpSyncServerExchange exchange,
                                                          Map<String, Object> args) {
        Principal principal = requirePrincipal(exchange);
        UUID breachId = requiredUuid(args, "breach_id");
        JsonNode evidenceRefs = nodeArg(args, "evidence_refs");
        return GovernedPrincipalScope.callAs(principal, () -> ok(BreachResponse.from(
                breachService.remediate(breachId, evidenceRefs,
                        ContractActor.agent(principal.id())))));
    }

    private McpSchema.CallToolResult waiveBreach(McpSyncServerExchange exchange,
                                                 Map<String, Object> args) {
        Principal principal = requirePrincipal(exchange);
        UUID breachId = requiredUuid(args, "breach_id");
        String rationale = requiredArg(args, "rationale");
        return GovernedPrincipalScope.callAs(principal, () -> ok(BreachResponse.from(
                breachService.waive(breachId, rationale,
                        ContractActor.agent(principal.id())))));
    }

    // -------------------------------------------------------------- plumbing

    private McpSchema.Tool tool(String name, String description, String inputSchemaJson) {
        return McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(mcpJsonMapper, inputSchemaJson)
                .build();
    }

    /**
     * Handler wrapper translating engine outcomes onto the §4.5/§8.3 wire:
     * structured {@code isError} results, never protocol errors.
     */
    private McpServerFeatures.SyncToolSpecification spec(
            McpSchema.Tool tool,
            BiFunction<McpSyncServerExchange, Map<String, Object>, McpSchema.CallToolResult> handler) {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        return handler.apply(exchange, request.arguments());
                    } catch (PrincipalRequired missingIdentity) {
                        return error("PRINCIPAL_REQUIRED", missingIdentity.getMessage());
                    } catch (ContractDeniedException denial) {
                        return denialError(denial);
                    } catch (WaiverHumanOnlyException waiver) {
                        return error("WAIVER_HUMAN_ONLY", waiver.getMessage());
                    } catch (ContractConflictException conflict) {
                        return error(conflict.getCode().name(), conflict.getMessage());
                    } catch (jakarta.persistence.EntityNotFoundException notFound) {
                        return error("NOT_FOUND", notFound.getMessage());
                    } catch (IllegalArgumentException e) {
                        return error("INVALID_ARGUMENT", e.getMessage());
                    } catch (Exception e) {
                        log.warn("[mcp] write tool {} failed", tool.name(), e);
                        return error("TOOL_ERROR", tool.name() + " failed: " + e.getMessage());
                    }
                })
                .build();
    }

    /**
     * §4.5 MCP denial shape: {@code isError=true} with structured content
     * {@code {code, contractKeys, clauseIds, reason, decisionId}} from the
     * exception's {@link Decision.Deny} so agents adapt rather than crash.
     */
    private McpSchema.CallToolResult denialError(ContractDeniedException denial) {
        Decision.Deny deny = denial.getDeny();
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("code", ContractDeniedException.CODE);
        structured.put("contractKeys", deny.contractKeys());
        structured.put("clauseIds",
                deny.clauseIds().stream().map(UUID::toString).toList());
        structured.put("reason", deny.reason());
        structured.put("decisionId",
                deny.decisionId() != null ? deny.decisionId().toString() : null);
        structured.put("message", denial.getMessage());
        String text;
        try {
            text = objectMapper.writeValueAsString(structured);
        } catch (Exception e) {
            text = ContractDeniedException.CODE + ": " + denial.getMessage();
        }
        return McpSchema.CallToolResult.builder()
                .isError(true)
                .structuredContent(structured)
                .addTextContent(text)
                .build();
    }

    /** Same wire JSON as REST (shared ObjectMapper): structuredContent + text mirror. */
    private McpSchema.CallToolResult ok(Object body) {
        try {
            return McpSchema.CallToolResult.builder()
                    .structuredContent(objectMapper.convertValue(body, MAP_TYPE))
                    .addTextContent(objectMapper.writeValueAsString(body))
                    .build();
        } catch (Exception e) {
            log.warn("[mcp] result serialization failed", e);
            return error("TOOL_ERROR", "result serialization failed: " + e.getMessage());
        }
    }

    private McpSchema.CallToolResult error(String code, String message) {
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("code", code);
        structured.put("message", message);
        String text;
        try {
            text = objectMapper.writeValueAsString(structured);
        } catch (Exception e) {
            text = code + ": " + message;
        }
        return McpSchema.CallToolResult.builder()
                .isError(true)
                .structuredContent(structured)
                .addTextContent(text)
                .build();
    }

    // ----------------------------------------------------- identity / lookups

    /** Write tools REQUIRE caller identity; the exception maps to a structured error. */
    private Principal requirePrincipal(McpSyncServerExchange exchange) {
        Object principal = exchange.transportContext()
                .get(ContractsMcpConfig.PRINCIPAL_CONTEXT_KEY);
        if (principal == null) {
            throw new PrincipalRequired();
        }
        return Principal.agent(principal.toString());
    }

    /** Distinct unchecked type so the wrapper can keep the read-tool error code. */
    private static final class PrincipalRequired extends RuntimeException {
        private PrincipalRequired() {
            super("governed write tools resolve caller identity from the '"
                    + ContractsMcpConfig.PRINCIPAL_HEADER + "' header — none was sent."
                    + " (Phase 1-4 localhost trust; spoofable until Phase 5 OIDC.)");
        }
    }

    private Contract requiredContract(Map<String, Object> args) {
        String key = requiredArg(args, "contract_key");
        return readService.resolve(key)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                        "No contract with key or id '" + key + "'"));
    }

    private Contract reload(Contract contract) {
        return readService.resolve(contract.getId().toString()).orElse(contract);
    }

    /** §5.5 party authority: the caller's principal must be bound to a party of the contract. */
    private ContractParty boundParty(Contract contract, Principal principal) {
        UUID partyId = optionalBoundParty(contract, principal);
        if (partyId == null) {
            throw new IllegalArgumentException("principal '" + principal.id()
                    + "' is not bound to any party of contract '" + contract.getContractKey()
                    + "' (contract_parties.mcp_principal_id, §5.5 party authority —"
                    + " advisory pre-Phase-5)");
        }
        return partyRepository.findById(partyId).orElseThrow();
    }

    private UUID optionalBoundParty(Contract contract, Principal principal) {
        return partyRepository.findByContractIdOrderBySequenceOrderAsc(contract.getId()).stream()
                .filter(p -> principal.id().equals(p.getMcpPrincipalId()))
                .map(ContractParty::getId)
                .findFirst()
                .orElse(null);
    }

    // ------------------------------------------------------------- arg parsing

    private static String stringArg(Map<String, Object> args, String name) {
        Object v = args == null ? null : args.get(name);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static String requiredArg(Map<String, Object> args, String name) {
        String v = stringArg(args, name);
        if (v == null) {
            throw new IllegalArgumentException("'" + name + "' is required");
        }
        return v;
    }

    private static UUID requiredUuid(Map<String, Object> args, String name) {
        String v = requiredArg(args, name);
        try {
            return UUID.fromString(v);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + name + "' is not a UUID: " + v);
        }
    }

    private static UUID optionalUuid(Map<String, Object> args, String name) {
        String v = stringArg(args, name);
        if (v == null) {
            return null;
        }
        try {
            return UUID.fromString(v);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + name + "' is not a UUID: " + v);
        }
    }

    private static double requiredNumber(Map<String, Object> args, String name) {
        Object v = args == null ? null : args.get(name);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                // falls through to the error below
            }
        }
        throw new IllegalArgumentException("'" + name + "' is required and must be a number");
    }

    /** Array args arrive as List from JSON; mirrored into a JsonNode for the engine. */
    private JsonNode nodeArg(Map<String, Object> args, String name) {
        Object v = args == null ? null : args.get(name);
        return v == null ? null : objectMapper.valueToTree(v);
    }

    private static <E extends Enum<E>> E requiredEnum(Class<E> type, Map<String, Object> args,
                                                      String name) {
        String raw = requiredArg(args, name);
        try {
            return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid value for '" + name + "': " + raw
                    + " (expected one of " + Arrays.stream(type.getEnumConstants())
                            .map(Enum::name).collect(Collectors.joining(", ")) + ")");
        }
    }
}
