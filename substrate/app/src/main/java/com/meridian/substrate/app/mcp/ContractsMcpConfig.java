package com.meridian.substrate.app.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.app.infrastructure.auth.AuthProperties;
import com.meridian.substrate.common.repo.AgentRegistrationRepository;
import com.meridian.substrate.common.repo.AgentThrottleBudgetRepository;
import com.meridian.substrate.common.repo.ContractPartyRepository;
import com.meridian.substrate.engine.agent.AgentControlConfirmationService;
import com.meridian.substrate.engine.agent.AgentLivenessService;
import com.meridian.substrate.engine.agent.AgentMetricService;
import com.meridian.substrate.engine.agent.AgentQueryService;
import com.meridian.substrate.engine.contract.gateway.AdvisoryCheckService;
import com.meridian.substrate.engine.contract.monitor.AttestationService;
import com.meridian.substrate.engine.contract.monitor.BreachService;
import com.meridian.substrate.engine.contract.monitor.MetricSampleService;
import com.meridian.substrate.engine.contract.registry.ContractReadService;
import com.meridian.substrate.engine.contract.registry.ContractRegistryService;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code substrate-contracts} MCP server hosting (engine-spec contracts.md
 * §8.1; ADR D8): official MCP Java SDK, <b>Streamable HTTP</b> transport
 * mounted at {@value #MCP_ENDPOINT} by the app module — tool logic lives in
 * engine services, these beans are thin transport/adapter wiring (keeps the
 * MCP SDK out of {@code engine}'s {@code java-library} dependency graph).
 * Gated by {@code substrate.contracts.mcp.enabled} (default true, §1).
 *
 * <p><b>Identity — the Phase 5 ladder (ADR-002 D8 revisit, landed):</b>
 * under {@code substrate.auth.mode=anonymous} (dev default), localhost trust +
 * the {@value #PRINCIPAL_HEADER} header → an AGENT principal carried through
 * the SDK's transport context to tool handlers — documented SPOOFABLE
 * (S9-equivalent; spec §15 #12). Under {@code substrate.auth.mode=oidc} that
 * header trust is DEAD (ignored with a WARN): {@code /mcp} sits behind the
 * resource-server chain (unauthenticated → 401 before the servlet), and the
 * principal is a claim of the VERIFIED client-credential JWT
 * ({@code substrate.auth.oidc.mcp.principal-claim}, default {@code sub},
 * fallback {@code azp}) — matched against
 * {@code agent_registrations.mcp_principal_id} /
 * {@code contract_parties.mcp_principal_id} by the agents.md §8.2 resolution
 * ladder exactly as before.
 *
 * <p>The endpoint is registered as a raw servlet, NOT a Spring MVC
 * controller: MCP JSON-RPC framing must never pass through
 * {@code ResponseEnvelopeAdvice} or {@code GlobalExceptionHandler} — the
 * envelope is a REST-surface contract (§4.5 keeps the MCP denial shape
 * separate by design).
 */
@Configuration
@ConditionalOnProperty(name = "substrate.contracts.mcp.enabled",
        havingValue = "true", matchIfMissing = true)
public class ContractsMcpConfig {

    private static final Logger log = LoggerFactory.getLogger(ContractsMcpConfig.class);

    /** §8.1 mount point. */
    public static final String MCP_ENDPOINT = "/mcp";

    /** Anonymous-mode caller-identity header (spoofable; DEAD under oidc — class javadoc). */
    public static final String PRINCIPAL_HEADER = "X-MCP-Principal";

    /** Transport-context key the header value travels under to tool handlers. */
    public static final String PRINCIPAL_CONTEXT_KEY = "substrate.mcp.principal";

    private static final String SERVER_NAME = "substrate-contracts";
    private static final String SERVER_VERSION = "0.1.0";

    /**
     * SDK JSON binding = the app's own Jackson 2 {@code ObjectMapper}
     * (JacksonConfig): MCP results serialize byte-identically to the REST
     * surface (ISO-8601 instants, null preservation) — the §14.5 parity
     * contract.
     */
    @Bean
    public McpJsonMapper contractsMcpJsonMapper(ObjectMapper objectMapper) {
        return new JacksonMcpJsonMapper(objectMapper);
    }

    @Bean
    public ContractsMcpTools contractsMcpTools(ContractReadService readService,
                                               AdvisoryCheckService advisoryCheckService,
                                               ObjectMapper objectMapper,
                                               McpJsonMapper contractsMcpJsonMapper) {
        return new ContractsMcpTools(readService, advisoryCheckService, objectMapper,
                contractsMcpJsonMapper);
    }

    /**
     * §8.3 governed write tools (3c). Receives the PROXIED engine service
     * beans, so every call transits the {@code @Governed} aspect →
     * {@code ContractGateway} with the AGENT principal scoped by the adapter
     * (§4.2 PEP 1; denial wire shape per §4.5).
     */
    @Bean
    public ContractsMcpWriteTools contractsMcpWriteTools(ContractReadService readService,
                                                         ContractRegistryService registryService,
                                                         AttestationService attestationService,
                                                         MetricSampleService metricSampleService,
                                                         BreachService breachService,
                                                         ContractPartyRepository partyRepository,
                                                         ObjectMapper objectMapper,
                                                         McpJsonMapper contractsMcpJsonMapper) {
        return new ContractsMcpWriteTools(readService, registryService, attestationService,
                metricSampleService, breachService, partyRepository, objectMapper,
                contractsMcpJsonMapper);
    }

    /**
     * agents.md §12 (6c): agent tools join the SAME server — no second MCP
     * server, one principal model, one gating config (ADR-003 D16). Gated by
     * {@code substrate.agents.mcp.enabled} (agents.md §1, default true);
     * disabling drops the tools while REST stays unaffected.
     */
    @Bean
    @ConditionalOnProperty(name = "substrate.agents.mcp.enabled",
            havingValue = "true", matchIfMissing = true)
    public AgentMcpTools agentMcpTools(AgentQueryService agentQueryService,
                                       AgentLivenessService agentLivenessService,
                                       AgentMetricService agentMetricService,
                                       AgentControlConfirmationService agentControlConfirmationService,
                                       AgentRegistrationRepository agentRegistrationRepository,
                                       AgentThrottleBudgetRepository agentThrottleBudgetRepository,
                                       ObjectMapper objectMapper,
                                       McpJsonMapper contractsMcpJsonMapper) {
        return new AgentMcpTools(agentQueryService, agentLivenessService, agentMetricService,
                agentControlConfirmationService, agentRegistrationRepository,
                agentThrottleBudgetRepository, objectMapper, contractsMcpJsonMapper);
    }

    /**
     * Streamable HTTP servlet transport. The context extractor lifts the
     * caller principal off EVERY request into the SDK transport context —
     * handler threads must never touch the servlet request directly (the SDK
     * may execute them off the container thread). Under oidc the extractor
     * runs on the container thread AFTER the security filter chain, so the
     * SecurityContext holds the verified {@link JwtAuthenticationToken}.
     */
    @Bean
    public HttpServletStreamableServerTransportProvider contractsMcpTransportProvider(
            McpJsonMapper contractsMcpJsonMapper, AuthProperties authProperties) {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(contractsMcpJsonMapper)
                .mcpEndpoint(MCP_ENDPOINT)
                .contextExtractor(request -> {
                    String principal = resolvePrincipal(request, authProperties);
                    return principal == null
                            ? McpTransportContext.EMPTY
                            : McpTransportContext.create(
                                    Map.of(PRINCIPAL_CONTEXT_KEY, principal));
                })
                .build();
    }

    /**
     * The D8 principal ladder: anonymous mode trusts {@value #PRINCIPAL_HEADER}
     * (Phase 1–4 posture, unchanged); oidc mode IGNORES it (WARN) and derives
     * the principal from the verified JWT — {@code principal-claim} (default
     * {@code sub}), falling back to {@code azp} for client-credential tokens
     * where the registered identity is the OAuth client id.
     */
    private static String resolvePrincipal(HttpServletRequest request,
                                           AuthProperties authProperties) {
        String header = request.getHeader(PRINCIPAL_HEADER);
        if (!authProperties.isOidc()) {
            return (header == null || header.isBlank()) ? null : header.trim();
        }
        if (header != null) {
            log.warn("Ignoring spoofable {} header under substrate.auth.mode=oidc — MCP"
                    + " principals bind to the verified client-credential token"
                    + " (ADR-002 D8 / agents.md §8)", PRINCIPAL_HEADER);
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            // The security chain 401s unauthenticated /mcp requests before the
            // servlet; this guard is defense in depth for permissive setups.
            return null;
        }
        Jwt jwt = jwtAuthentication.getToken();
        String claim = authProperties.getOidc().getMcp().getPrincipalClaim();
        String principal = (claim == null || claim.isBlank()) ? null : jwt.getClaimAsString(claim);
        if (principal == null || principal.isBlank()) {
            principal = jwt.getClaimAsString("azp");
        }
        return (principal == null || principal.isBlank()) ? null : principal.trim();
    }

    /** Mounts the transport servlet at {@value #MCP_ENDPOINT} (async for SSE streams). */
    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> contractsMcpServlet(
            HttpServletStreamableServerTransportProvider contractsMcpTransportProvider) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(contractsMcpTransportProvider, MCP_ENDPOINT);
        registration.setName("contractsMcpServlet");
        registration.setAsyncSupported(true);
        registration.setLoadOnStartup(1);
        return registration;
    }

    /**
     * The sync MCP server over the transport. {@code immediateExecution} runs
     * tool handlers on the request thread — engine services are plain
     * {@code @Transactional} Spring beans and tool calls are short reads, so
     * no scheduler indirection is wanted.
     */
    @Bean(destroyMethod = "close")
    public McpSyncServer contractsMcpServer(
            HttpServletStreamableServerTransportProvider contractsMcpTransportProvider,
            McpJsonMapper contractsMcpJsonMapper,
            ContractsMcpTools contractsMcpTools,
            ContractsMcpWriteTools contractsMcpWriteTools,
            ObjectProvider<AgentMcpTools> agentMcpTools) {
        List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();
        tools.addAll(contractsMcpTools.specifications());        // §8.2 read tools
        tools.addAll(contractsMcpWriteTools.specifications());   // §8.3 governed write tools
        // agents.md §12 (6c): agent tools on the SAME server, when enabled.
        agentMcpTools.ifAvailable(agents -> tools.addAll(agents.specifications()));
        return McpServer.sync(contractsMcpTransportProvider)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .jsonMapper(contractsMcpJsonMapper)
                .immediateExecution(true)
                .tools(tools)
                .build();
    }
}
