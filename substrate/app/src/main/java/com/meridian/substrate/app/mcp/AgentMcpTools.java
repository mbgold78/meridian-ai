package com.meridian.substrate.app.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.enums.AgentOperationalMode;
import com.meridian.substrate.common.domain.enums.AgentWireStatus;
import com.meridian.substrate.common.domain.enums.ContractActorType;
import com.meridian.substrate.common.repo.AgentRegistrationRepository;
import com.meridian.substrate.common.repo.AgentThrottleBudgetRepository;
import com.meridian.substrate.engine.agent.AgentActor;
import com.meridian.substrate.engine.agent.AgentControlConfirmationService;
import com.meridian.substrate.engine.agent.AgentLivenessService;
import com.meridian.substrate.engine.agent.AgentMetricService;
import com.meridian.substrate.engine.agent.AgentQueryService;
import com.meridian.substrate.engine.agent.exception.AgentConflictException;
import com.meridian.substrate.engine.agent.exception.AgentNotFoundException;
import com.meridian.substrate.engine.agent.read.AgentBudgetView;
import com.meridian.substrate.engine.agent.read.AgentFleetRow;
import com.meridian.substrate.engine.agent.read.AgentSightingView;
import com.meridian.substrate.engine.contract.gateway.ContractDeniedException;
import com.meridian.substrate.engine.contract.gateway.Decision;
import com.meridian.substrate.engine.contract.gateway.GovernedPrincipalScope;
import com.meridian.substrate.engine.contract.gateway.Principal;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * §12 agent tools on the SAME {@code substrate-contracts} {@code /mcp}
 * server — no second server, one principal model, one gating config
 * (engine-spec agents.md §12; ADR-003 D16). Thin adapters onto engine
 * services; logic lives in {@code engine.agent}.
 *
 * <p><b>Reads (ungoverned):</b> {@code list_agents}, {@code get_agent},
 * {@code get_my_registration} (caller-identity-resolved — verbatim mandate,
 * gates + standing, status, oversight chain; the P19 keystone for agents),
 * {@code get_my_control_state} (mode + budgets + schedule window — the P11
 * client-side self-limiting read).
 *
 * <p><b>Governed writes:</b> {@code agent_heartbeat},
 * {@code report_agent_activity}, {@code acknowledge_control} — each wraps
 * its {@code @Governed} engine half in {@link GovernedPrincipalScope} with
 * the AGENT principal from {@code X-MCP-Principal}, so the §8 registry gate
 * sees every call. Gate denials surface as structured {@code isError}
 * results with code {@code AGENT_GATE_DENIAL} (§8.5) — runtimes adapt
 * rather than crash.
 *
 * <p><b>NO lifecycle tools exist here</b> — deploy/promote/pause/resume/
 * throttle/kill/retire/intervention are REST-only, human-only (invariant
 * I11; the §8.4 built-in guards are defense in depth).
 */
public class AgentMcpTools {

    private static final Logger log = LoggerFactory.getLogger(AgentMcpTools.class);
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE =
            new TypeReference<>() {
            };

    private final AgentQueryService queryService;
    private final AgentLivenessService livenessService;
    private final AgentMetricService metricService;
    private final AgentControlConfirmationService controlConfirmationService;
    private final AgentRegistrationRepository registrationRepository;
    private final AgentThrottleBudgetRepository throttleBudgetRepository;
    private final ObjectMapper objectMapper;
    private final McpJsonMapper mcpJsonMapper;

    public AgentMcpTools(AgentQueryService queryService,
                         AgentLivenessService livenessService,
                         AgentMetricService metricService,
                         AgentControlConfirmationService controlConfirmationService,
                         AgentRegistrationRepository registrationRepository,
                         AgentThrottleBudgetRepository throttleBudgetRepository,
                         ObjectMapper objectMapper,
                         McpJsonMapper mcpJsonMapper) {
        this.queryService = queryService;
        this.livenessService = livenessService;
        this.metricService = metricService;
        this.controlConfirmationService = controlConfirmationService;
        this.registrationRepository = registrationRepository;
        this.throttleBudgetRepository = throttleBudgetRepository;
        this.objectMapper = objectMapper;
        this.mcpJsonMapper = mcpJsonMapper;
    }

    /** The §12 tool inventory, in spec order. */
    public List<McpServerFeatures.SyncToolSpecification> specifications() {
        return List.of(
                spec(tool("list_agents",
                        "Read (ungoverned): agent fleet rows (§7.3 AgentFleetRow shape)."
                                + " Retired registrations are excluded (default-exclusion"
                                + " contract).",
                        """
                        {"type":"object","properties":{
                          "domain_id":{"type":"string","description":"Domain UUID; omit for all domains"},
                          "status":{"type":"string","description":"live | shadow | paused | intervention-required"}},
                         "additionalProperties":false}"""),
                        this::listAgents),
                spec(tool("get_agent",
                        "Read (ungoverned): full agent detail incl. criteria gates + standing"
                                + " (§7.3 AgentDetail shape).",
                        """
                        {"type":"object","properties":{
                          "agent_key":{"type":"string","description":"agent_key or registration UUID"},
                          "domain_id":{"type":"string","description":"required when the key exists in several domains"}},
                         "required":["agent_key"],"additionalProperties":false}"""),
                        this::getAgent),
                spec(tool("get_my_registration",
                        "Read: the CALLER's registration, resolved from X-MCP-Principal — the"
                                + " verbatim mandate (deploymentCriteria), criteria gates +"
                                + " standing, status, oversight chain. Sibling of"
                                + " get_obligations_for_principal (P19).",
                        """
                        {"type":"object","properties":{},"additionalProperties":false}"""),
                        this::getMyRegistration),
                spec(tool("get_my_control_state",
                        "Read: the caller's operational mode, read-only posture, active"
                                + " budgets and schedule window — the P11 client-side"
                                + " self-limiting read.",
                        """
                        {"type":"object","properties":{},"additionalProperties":false}"""),
                        this::getMyControlState),
                spec(tool("agent_heartbeat",
                        "GOVERNED: record a HEARTBEAT sighting (hourly-bucket deduped). A"
                                + " suspended principal's heartbeat is denied-but-recorded:"
                                + " the touch is sighted, the violation evented, and the"
                                + " oversight chain signalled (§6.4 zombie flow).",
                        """
                        {"type":"object","properties":{
                          "activity_summary":{"type":"string"}},
                         "additionalProperties":false}"""),
                        this::agentHeartbeat),
                spec(tool("report_agent_activity",
                        "GOVERNED: activity note (sighting detail) and/or metric samples"
                                + " (idempotent upsert on (agent, metric_key, period_key);"
                                + " 'adherence' feeds the fleet-row number).",
                        """
                        {"type":"object","properties":{
                          "activity_note":{"type":"string"},
                          "metrics":{"type":"array","items":{"type":"object","properties":{
                            "metric_key":{"type":"string"},
                            "value":{"type":"number"},
                            "period_key":{"type":"string"}},
                            "required":["metric_key","value","period_key"],
                            "additionalProperties":false}}},
                         "additionalProperties":false}"""),
                        this::reportAgentActivity),
                spec(tool("acknowledge_control",
                        "GOVERNED: the runtime acknowledges a pending control order"
                                + " (CONTROL_ACKNOWLEDGED, M17) — the ONE write a"
                                + " paused/retired principal retains; acknowledging shutdown"
                                + " is the compliant act. Weaker than human confirmation;"
                                + " neither derives from the other.",
                        """
                        {"type":"object","properties":{
                          "idempotency_key":{"type":"string","description":"optional; generated when omitted"}},
                         "additionalProperties":false}"""),
                        this::acknowledgeControl));
    }

    // ------------------------------------------------------------- read tools

    private McpSchema.CallToolResult listAgents(McpSyncServerExchange exchange,
                                                Map<String, Object> args) {
        AgentWireStatus status = optionalStatus(args);
        UUID domainId = optionalUuid(args, "domain_id");
        List<AgentFleetRow> rows = new ArrayList<>();
        List<UUID> domains = domainId != null ? List.of(domainId)
                : registrationRepository.findByOperationalModeNot(AgentOperationalMode.RETIRED)
                        .stream().map(AgentRegistration::getDomainId).distinct().toList();
        for (UUID domain : domains) {
            rows.addAll(queryService.fleet(domain, status, false));
        }
        return ok(rows);
    }

    private McpSchema.CallToolResult getAgent(McpSyncServerExchange exchange,
                                              Map<String, Object> args) {
        String agentKey = requiredArg(args, "agent_key");
        return ok(queryService.detailByIdOrKey(agentKey, optionalUuid(args, "domain_id")));
    }

    private McpSchema.CallToolResult getMyRegistration(McpSyncServerExchange exchange,
                                                       Map<String, Object> args) {
        AgentRegistration reg = requireMyRegistration(exchange);
        return ok(queryService.detail(reg));
    }

    private McpSchema.CallToolResult getMyControlState(McpSyncServerExchange exchange,
                                                       Map<String, Object> args) {
        AgentRegistration reg = requireMyRegistration(exchange);
        ObjectNode state = objectMapper.createObjectNode();
        state.put("agentKey", reg.getAgentKey());
        state.put("operationalMode", reg.getOperationalMode().wireValue());
        state.put("readOnly", reg.isReadOnly());
        state.set("scheduleWindow", reg.getScheduleWindow());
        if (reg.getSamplingPct() == null) {
            state.putNull("samplingPct");
        } else {
            state.put("samplingPct", reg.getSamplingPct());
        }
        state.put("openInterventionCount", reg.getOpenInterventionCount());
        ArrayNode budgets = state.putArray("budgets");
        throttleBudgetRepository.findByAgentRegistrationIdAndActiveTrue(reg.getId()).stream()
                .map(AgentBudgetView::from)
                .forEach(b -> budgets.add(objectMapper.valueToTree(b)));
        return ok(state);
    }

    // --------------------------------------------------------- governed writes

    private McpSchema.CallToolResult agentHeartbeat(McpSyncServerExchange exchange,
                                                    Map<String, Object> args) {
        Principal principal = requirePrincipal(exchange);
        AgentRegistration reg = requireMyRegistration(exchange);
        String summary = stringArg(args, "activity_summary");
        return GovernedPrincipalScope.callAs(principal, () -> {
            var sighting = livenessService.heartbeat(reg.getId(), summary);
            ObjectNode result = objectMapper.createObjectNode();
            result.put("recorded", sighting.isPresent());
            if (sighting.isPresent()) {
                result.set("sighting", objectMapper.valueToTree(
                        AgentSightingView.from(sighting.get())));
            } else {
                result.put("note", "deduped — a sighting already exists in the current bucket"
                        + " (liveness unchanged is honest, not an error)");
            }
            return ok(result);
        });
    }

    private McpSchema.CallToolResult reportAgentActivity(McpSyncServerExchange exchange,
                                                         Map<String, Object> args) {
        Principal principal = requirePrincipal(exchange);
        AgentRegistration reg = requireMyRegistration(exchange);
        String note = stringArg(args, "activity_note");
        List<AgentMetricService.ActivityMetric> metrics = new ArrayList<>();
        Object raw = args == null ? null : args.get("metrics");
        if (raw instanceof List<?> entries) {
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> metric = (Map<String, Object>) m;
                    metrics.add(new AgentMetricService.ActivityMetric(
                            requiredArg(metric, "metric_key"),
                            requiredNumber(metric, "value"),
                            requiredArg(metric, "period_key")));
                }
            }
        }
        return GovernedPrincipalScope.callAs(principal, () -> ok(
                metricService.reportActivity(reg.getId(), note, metrics, principal.id())
                        .stream()
                        .map(com.meridian.substrate.engine.agent.read.AgentMetricSampleView::from)
                        .toList()));
    }

    private McpSchema.CallToolResult acknowledgeControl(McpSyncServerExchange exchange,
                                                        Map<String, Object> args) {
        Principal principal = requirePrincipal(exchange);
        AgentRegistration reg = requireMyRegistration(exchange);
        String idempotencyKey = Optional.ofNullable(stringArg(args, "idempotency_key"))
                .orElse("mcp-ack:" + UUID.randomUUID());
        return GovernedPrincipalScope.callAs(principal, () -> {
            AgentRegistration after = controlConfirmationService.acknowledgeControl(reg.getId(),
                    idempotencyKey,
                    new AgentActor(null, principal.id(), ContractActorType.AGENT));
            ObjectNode result = objectMapper.createObjectNode();
            result.put("agentKey", after.getAgentKey());
            result.put("pendingVerb", after.getControlPendingVerb());
            result.put("acknowledgedAt", after.getControlAcknowledgedAt() == null ? null
                    : after.getControlAcknowledgedAt().toString());
            result.put("note", "runtime acknowledgment recorded — a WEAKER fact than human"
                    + " confirmation; confirm-control remains a human REST act (ADR-003 D6)");
            return ok(result);
        });
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
     * Outcome translation onto the §4.5/§8.5 wire: structured
     * {@code isError} results, never protocol errors. Gate denials carry
     * code {@code AGENT_GATE_DENIAL} + the §8.3 reason + decisionId.
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
                    } catch (NotRegistered notRegistered) {
                        return error("AGENT_NOT_REGISTERED", notRegistered.getMessage());
                    } catch (ContractDeniedException denial) {
                        return denialError(denial);
                    } catch (AgentConflictException conflict) {
                        return error(conflict.getCode().name(), conflict.getMessage());
                    } catch (AgentNotFoundException notFound) {
                        return error("AGENT_NOT_FOUND", notFound.getMessage());
                    } catch (IllegalArgumentException e) {
                        return error("INVALID_ARGUMENT", e.getMessage());
                    } catch (Exception e) {
                        log.warn("[mcp] agent tool {} failed", tool.name(), e);
                        return error("TOOL_ERROR", tool.name() + " failed: " + e.getMessage());
                    }
                })
                .build();
    }

    /** §8.5 structured denial — {@code {code, reason, decisionId, message}}. */
    private McpSchema.CallToolResult denialError(ContractDeniedException denial) {
        Decision.Deny deny = denial.getDeny();
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("code", denial.code());
        structured.put("reason", deny.reason());
        structured.put("contractKeys", deny.contractKeys());
        structured.put("clauseIds", deny.clauseIds().stream().map(UUID::toString).toList());
        structured.put("decisionId",
                deny.decisionId() != null ? deny.decisionId().toString() : null);
        structured.put("message", denial.getMessage());
        String text;
        try {
            text = objectMapper.writeValueAsString(structured);
        } catch (Exception e) {
            text = denial.code() + ": " + denial.getMessage();
        }
        return McpSchema.CallToolResult.builder()
                .isError(true)
                .structuredContent(structured)
                .addTextContent(text)
                .build();
    }

    private McpSchema.CallToolResult ok(Object body) {
        try {
            if (body instanceof List<?> list) {
                ObjectNode wrapper = objectMapper.createObjectNode();
                wrapper.set("items", objectMapper.valueToTree(list));
                return McpSchema.CallToolResult.builder()
                        .structuredContent(objectMapper.convertValue(wrapper, MAP_TYPE))
                        .addTextContent(objectMapper.writeValueAsString(list))
                        .build();
            }
            return McpSchema.CallToolResult.builder()
                    .structuredContent(objectMapper.convertValue(body, MAP_TYPE))
                    .addTextContent(objectMapper.writeValueAsString(body))
                    .build();
        } catch (Exception e) {
            log.warn("[mcp] agent tool result serialization failed", e);
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

    // ----------------------------------------------------- identity / parsing

    private Principal requirePrincipal(McpSyncServerExchange exchange) {
        Object principal = exchange.transportContext()
                .get(ContractsMcpConfig.PRINCIPAL_CONTEXT_KEY);
        if (principal == null) {
            throw new PrincipalRequired();
        }
        return Principal.agent(principal.toString());
    }

    /** Caller-identity resolution for the my_* + governed tools (§12). */
    private AgentRegistration requireMyRegistration(McpSyncServerExchange exchange) {
        Principal principal = requirePrincipal(exchange);
        return registrationRepository.findByMcpPrincipalId(principal.id())
                .orElseThrow(() -> new NotRegistered(principal.id()));
    }

    private static final class PrincipalRequired extends RuntimeException {
        private PrincipalRequired() {
            super("agent tools require caller identity — none was resolved."
                    + " anonymous mode: send the '" + ContractsMcpConfig.PRINCIPAL_HEADER
                    + "' header (localhost trust, spoofable by design); oidc mode: the"
                    + " principal is a claim of the verified bearer token"
                    + " (substrate.auth.oidc.mcp.principal-claim, default 'sub').");
        }
    }

    private static final class NotRegistered extends RuntimeException {
        private NotRegistered(String principalId) {
            super("principal '" + principalId + "' is not bound to any agent registration"
                    + " (agent_registrations.mcp_principal_id) — substrate grants no agent"
                    + " write surface without one; deploy a registration and bind the"
                    + " principal (POST /api/v1/agents, M18)");
        }
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

    private static AgentWireStatus optionalStatus(Map<String, Object> args) {
        String raw = stringArg(args, "status");
        if (raw == null) {
            return null;
        }
        try {
            return AgentWireStatus.valueOf(
                    raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'status' must be one of live | shadow | paused"
                    + " | intervention-required | retired (got '" + raw + "')");
        }
    }
}
