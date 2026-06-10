package com.meridian.substrate.engine.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.ai.config.LlmProperties;
import com.meridian.substrate.engine.ai.persona.PersonaContext;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dispatches tool invocations by name, enforcing persona access control
 * and a configurable execution timeout.
 *
 * <p><b>Port note (port-map §2.3 fix):</b> homepoc used an unbounded
 * {@code newCachedThreadPool}; substrate bounds the dispatch executor to
 * {@code substrate.engine.tools.dispatch-pool-size} threads (default 8) so a
 * burst of slow tools cannot exhaust the JVM under production load.
 */
@Service
public class ToolDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ToolDispatcher.class);

    private final ToolRegistry registry;
    private final LlmProperties llmProperties;
    private final ObjectMapper mapper;
    private final ExecutorService executor;

    public ToolDispatcher(ToolRegistry registry,
                          LlmProperties llmProperties,
                          ToolExecutionProperties executionProperties,
                          ObjectMapper mapper) {
        this.registry = registry;
        this.llmProperties = llmProperties;
        this.mapper = mapper;
        AtomicInteger threadIndex = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(
                Math.max(1, executionProperties.getDispatchPoolSize()),
                r -> {
                    Thread t = new Thread(r, "tool-dispatch-" + threadIndex.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
    }

    /**
     * Dispatch a tool call by name with the given arguments and persona context.
     *
     * @param toolName  the tool to invoke
     * @param arguments JSON arguments from the LLM
     * @param persona   the current persona context
     * @return the JSON result from the tool, or an error node on failure
     */
    public JsonNode dispatch(String toolName, JsonNode arguments, PersonaContext persona) {
        log.debug("Dispatching tool '{}' for persona '{}' with args: {}",
                toolName, persona.getPersonaLevel(), arguments);

        // Look up tool
        Tool tool;
        try {
            tool = registry.getTool(toolName);
        } catch (Exception e) {
            log.warn("Tool lookup failed: {}", e.getMessage());
            return errorNode("Unknown tool: " + toolName);
        }

        // Validate persona access
        if (!registry.isAllowed(persona, toolName)) {
            log.warn("Persona '{}' denied access to tool '{}'", persona.getPersonaLevel(), toolName);
            return errorNode("Access denied: persona '" + persona.getPersonaLevel()
                    + "' is not authorized to use tool '" + toolName + "'");
        }

        // Execute with timeout. Capture MDC from the submitting thread and re-apply it
        // on the dispatch thread so request-scoped logging context (e.g. requestId) flows
        // across the executor boundary.
        long timeoutMs = llmProperties.getTools().getDispatchTimeoutMs();
        Map<String, String> callerMdc = MDC.getCopyOfContextMap();
        Future<JsonNode> future = executor.submit(() -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (callerMdc != null) {
                MDC.setContextMap(callerMdc);
            } else {
                MDC.clear();
            }
            try {
                return tool.execute(arguments, persona);
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        });
        try {
            JsonNode result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            log.debug("Tool '{}' completed successfully", toolName);
            return result;
        } catch (TimeoutException e) {
            future.cancel(true);
            log.error("Tool '{}' timed out after {} ms", toolName, timeoutMs);
            return errorNode("Tool execution timed out after " + timeoutMs + " ms");
        } catch (ExecutionException e) {
            log.error("Tool '{}' execution failed: {}", toolName, e.getCause().getMessage(), e.getCause());
            return errorNode("Tool execution failed: " + e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorNode("Tool execution interrupted");
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private JsonNode errorNode(String message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("error", true);
        node.put("message", message);
        return node;
    }
}
