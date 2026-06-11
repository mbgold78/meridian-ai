package com.meridian.substrate.app.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.enums.BreachStatus;
import com.meridian.substrate.common.domain.enums.CommitmentComplianceState;
import com.meridian.substrate.common.domain.enums.ContractStatus;
import com.meridian.substrate.engine.contract.gateway.AdvisoryCheckService;
import com.meridian.substrate.engine.contract.read.BreachResponse;
import com.meridian.substrate.engine.contract.read.CommitmentRowResponse;
import com.meridian.substrate.engine.contract.read.ContractCardResponse;
import com.meridian.substrate.engine.contract.read.EnforcementDecisionResponse;
import com.meridian.substrate.engine.contract.registry.ContractReadService;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * The §8.2 ungoverned READ tools of the {@code substrate-contracts} MCP
 * server (engine-spec contracts.md §8.1/§8.2; ADR D8) — thin app-side
 * adapters; ALL logic lives in engine services ({@code ContractReadService},
 * {@code AdvisoryCheckService}), keeping the MCP SDK out of {@code engine}'s
 * dependency graph.
 *
 * <p>Results carry the SAME JSON the REST surface serves (shared
 * {@code ObjectMapper}) as both {@code structuredContent} and a text-content
 * JSON mirror — §14.5 list/get parity is by construction, modulo the REST
 * {@code {data, meta, errors}} envelope which is an MVC-advice concern and
 * deliberately absent here. List results are wrapped in a single-key object
 * ({@code {"contracts": […]}} etc.) because MCP {@code structuredContent}
 * must be a JSON object.
 *
 * <p>Caller identity: {@code X-MCP-Principal} header → transport context
 * (see {@link ContractsMcpConfig}); Phase 1–4 localhost trust, documented
 * spoofable (S9-equivalent, spec §15 #12). Tool errors are
 * {@code isError=true} results with structured {@code {code, message}} so
 * agents adapt rather than crash (§4.5 posture); the §4.5
 * {@code CONTRACT_DENIAL} shape is reserved for the 3c governed write tools.
 *
 * <p>The §8.3 governed write tools land with 3b/3c behavior — not here.
 */
public class ContractsMcpTools {

    private static final Logger log = LoggerFactory.getLogger(ContractsMcpTools.class);
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE =
            new TypeReference<>() {
            };

    private final ContractReadService readService;
    private final AdvisoryCheckService advisoryCheckService;
    private final ObjectMapper objectMapper;
    private final McpJsonMapper mcpJsonMapper;

    public ContractsMcpTools(ContractReadService readService,
                             AdvisoryCheckService advisoryCheckService,
                             ObjectMapper objectMapper,
                             McpJsonMapper mcpJsonMapper) {
        this.readService = readService;
        this.advisoryCheckService = advisoryCheckService;
        this.objectMapper = objectMapper;
        this.mcpJsonMapper = mcpJsonMapper;
    }

    /** The §8.2 read-tool inventory, in spec order. */
    public List<McpServerFeatures.SyncToolSpecification> specifications() {
        return List.of(
                spec(tool("list_contracts",
                        "List coordination contracts as card summaries (§7.1 shape). All"
                                + " filters optional: domain_id (UUID), status"
                                + " (PROPOSED|ACTIVE|DECLINED), party (case-insensitive"
                                + " substring over party labels).",
                        """
                        {"type":"object","properties":{
                          "domain_id":{"type":"string","description":"Domain UUID"},
                          "status":{"type":"string","description":"PROPOSED | ACTIVE | DECLINED"},
                          "party":{"type":"string","description":"Case-insensitive substring over party labels"}},
                         "additionalProperties":false}"""),
                        this::listContracts),
                spec(tool("get_contract",
                        "Full contract detail by contract_key (or UUID): parties +"
                                + " commitments + compliance, enforcement text VERBATIM,"
                                + " decline triggers with armed standing, responses, breach"
                                + " summary.",
                        """
                        {"type":"object","properties":{
                          "contract_key":{"type":"string","description":"Contract slug (e.g. co-tribal-knowledge-depopulation) or UUID"}},
                         "required":["contract_key"],"additionalProperties":false}"""),
                        this::getContract),
                spec(tool("get_commitments",
                        "Commitment rows with compliance state. All filters optional:"
                                + " contract_key, party (substring), compliance_state"
                                + " (UNEVALUATED|COMPLIANT|AT_RISK|BREACHED|WAIVED).",
                        """
                        {"type":"object","properties":{
                          "contract_key":{"type":"string"},
                          "party":{"type":"string"},
                          "compliance_state":{"type":"string"}},
                         "additionalProperties":false}"""),
                        this::getCommitments),
                spec(tool("get_obligations_for_principal",
                        "What the calling party owes now (caller identity from the"
                                + " X-MCP-Principal header): open commitments, due dates,"
                                + " cadence, next period_key, missing attestations — across"
                                + " every ACTIVE contract whose party is bound to this"
                                + " principal.",
                        """
                        {"type":"object","properties":{},"additionalProperties":false}"""),
                        this::getObligationsForPrincipal),
                spec(tool("get_breaches",
                        "Breach rows. Filters optional: contract_key, status"
                                + " (DETECTED|SURFACED|ACKNOWLEDGED|REMEDIATED|WAIVED).",
                        """
                        {"type":"object","properties":{
                          "contract_key":{"type":"string"},
                          "status":{"type":"string"}},
                         "additionalProperties":false}"""),
                        this::getBreaches),
                spec(tool("get_enforcement_decisions",
                        "Enforcement-decision (PDP) log rows. Filters optional:"
                                + " contract_key, actor (verbatim actor id), since (ISO-8601"
                                + " instant).",
                        """
                        {"type":"object","properties":{
                          "contract_key":{"type":"string"},
                          "actor":{"type":"string"},
                          "since":{"type":"string","description":"ISO-8601 instant, e.g. 2026-06-01T00:00:00Z"}},
                         "additionalProperties":false}"""),
                        this::getEnforcementDecisions),
                spec(tool("check_action",
                        "ADVISORY dry-run of the contract gateway (PDP) for an action:"
                                + " returns {verdict, matchedClauses, explanation} with"
                                + " verdict ALLOW | ALLOW_WITH_FLAG | BLOCK. Cooperative"
                                + " preflight only — the real check happens at write time;"
                                + " never records an enforcement decision.",
                        """
                        {"type":"object","properties":{
                          "action_type":{"type":"string","description":"e.g. agent.promote, apply_dispatch_result"},
                          "target_entity_type":{"type":"string"},
                          "target_entity_id":{"type":"string"},
                          "payload":{"type":"object","description":"Payload summary the guards' predicates evaluate against"}},
                         "required":["action_type"],"additionalProperties":false}"""),
                        this::checkAction));
    }

    // ------------------------------------------------------------- handlers

    record ContractsPayload(List<ContractCardResponse> contracts) {
    }

    record CommitmentsPayload(List<CommitmentRowResponse> commitments) {
    }

    record BreachesPayload(List<BreachResponse> breaches) {
    }

    record DecisionsPayload(List<EnforcementDecisionResponse> decisions) {
    }

    private McpSchema.CallToolResult listContracts(McpSyncServerExchange exchange,
                                                   Map<String, Object> args) {
        UUID domainId = uuidArg(args, "domain_id");
        ContractStatus status = enumArg(ContractStatus.class, args, "status");
        return ok(new ContractsPayload(
                readService.cardsForMcp(domainId, status, stringArg(args, "party"))));
    }

    private McpSchema.CallToolResult getContract(McpSyncServerExchange exchange,
                                                 Map<String, Object> args) {
        String key = requiredArg(args, "contract_key");
        return readService.resolve(key)
                .map(contract -> ok(readService.detail(contract)))
                .orElseGet(() -> error("CONTRACT_NOT_FOUND",
                        "No contract with key or id '" + key + "'"));
    }

    private McpSchema.CallToolResult getCommitments(McpSyncServerExchange exchange,
                                                    Map<String, Object> args) {
        Contract contract = optionalContract(args);
        if (contract == null && stringArg(args, "contract_key") != null) {
            return error("CONTRACT_NOT_FOUND",
                    "No contract with key or id '" + stringArg(args, "contract_key") + "'");
        }
        return ok(new CommitmentsPayload(readService.commitmentRowsForMcp(contract,
                stringArg(args, "party"),
                enumArg(CommitmentComplianceState.class, args, "compliance_state"))));
    }

    private McpSchema.CallToolResult getObligationsForPrincipal(McpSyncServerExchange exchange,
                                                                Map<String, Object> args) {
        Object principal = exchange.transportContext()
                .get(ContractsMcpConfig.PRINCIPAL_CONTEXT_KEY);
        if (principal == null) {
            return error("PRINCIPAL_REQUIRED",
                    "get_obligations_for_principal resolves caller identity from the '"
                            + ContractsMcpConfig.PRINCIPAL_HEADER + "' header — none was sent."
                            + " (Phase 1-4 localhost trust; spoofable until Phase 5 OIDC.)");
        }
        return ok(readService.obligationsForPrincipal(principal.toString()));
    }

    private McpSchema.CallToolResult getBreaches(McpSyncServerExchange exchange,
                                                 Map<String, Object> args) {
        Contract contract = optionalContract(args);
        if (contract == null && stringArg(args, "contract_key") != null) {
            return error("CONTRACT_NOT_FOUND",
                    "No contract with key or id '" + stringArg(args, "contract_key") + "'");
        }
        return ok(new BreachesPayload(readService.breachesForMcp(contract,
                enumArg(BreachStatus.class, args, "status"))));
    }

    private McpSchema.CallToolResult getEnforcementDecisions(McpSyncServerExchange exchange,
                                                             Map<String, Object> args) {
        Contract contract = optionalContract(args);
        if (contract == null && stringArg(args, "contract_key") != null) {
            return error("CONTRACT_NOT_FOUND",
                    "No contract with key or id '" + stringArg(args, "contract_key") + "'");
        }
        return ok(new DecisionsPayload(readService.decisionsForMcp(contract,
                stringArg(args, "actor"), instantArg(args, "since"))));
    }

    private McpSchema.CallToolResult checkAction(McpSyncServerExchange exchange,
                                                 Map<String, Object> args) {
        String actionType = requiredArg(args, "action_type");
        Object payload = args == null ? null : args.get("payload");
        JsonNode payloadNode = payload == null ? null : objectMapper.valueToTree(payload);
        return ok(advisoryCheckService.check(actionType,
                stringArg(args, "target_entity_type"),
                stringArg(args, "target_entity_id"),
                payloadNode));
    }

    // -------------------------------------------------------------- plumbing

    private McpSchema.Tool tool(String name, String description, String inputSchemaJson) {
        return McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(mcpJsonMapper, inputSchemaJson)
                .build();
    }

    private McpServerFeatures.SyncToolSpecification spec(
            McpSchema.Tool tool,
            BiFunction<McpSyncServerExchange, Map<String, Object>, McpSchema.CallToolResult> handler) {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        return handler.apply(exchange, request.arguments());
                    } catch (IllegalArgumentException e) {
                        return error("INVALID_ARGUMENT", e.getMessage());
                    } catch (Exception e) {
                        log.warn("[mcp] tool {} failed", tool.name(), e);
                        return error("TOOL_ERROR",
                                tool.name() + " failed: " + e.getMessage());
                    }
                })
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

    /** Nullable contract scope: resolves {@code contract_key} when present, else null. */
    private Contract optionalContract(Map<String, Object> args) {
        String key = stringArg(args, "contract_key");
        return key == null ? null : readService.resolve(key).orElse(null);
    }

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

    private static UUID uuidArg(Map<String, Object> args, String name) {
        String v = stringArg(args, name);
        try {
            return v == null ? null : UUID.fromString(v);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + name + "' is not a UUID: " + v);
        }
    }

    private static Instant instantArg(Map<String, Object> args, String name) {
        String v = stringArg(args, name);
        try {
            return v == null ? null : Instant.parse(v);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "'" + name + "' is not an ISO-8601 instant: " + v);
        }
    }

    /** Case-insensitive enum filter; mirrors ContractController.parseEnum (same 400-class posture). */
    private static <E extends Enum<E>> E enumArg(Class<E> type, Map<String, Object> args,
                                                 String name) {
        String raw = stringArg(args, name);
        if (raw == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid value for '" + name + "': " + raw
                    + " (expected one of " + Arrays.stream(type.getEnumConstants())
                            .map(Enum::name).collect(Collectors.joining(", ")) + ")");
        }
    }
}
