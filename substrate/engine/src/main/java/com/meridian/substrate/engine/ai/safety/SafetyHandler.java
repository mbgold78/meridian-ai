package com.meridian.substrate.engine.ai.safety;

import com.meridian.substrate.common.ai.CompletionResponse;
import com.meridian.substrate.common.ai.config.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service for detecting and handling LLM refusals. Checks completion responses
 * for refusal patterns and provides fallback messages from configuration
 * ({@code substrate.llm.safety.refusal-fallback-message}).
 *
 * <p><b>Port note (port-map §2.3 / dead-code mirage):</b> homepoc shipped this
 * class with ZERO callers — {@code ChatInteraction.refusalDetected} was
 * hardcoded {@code false}. In substrate it is a live code path: the
 * orchestrator routes its final text through {@link #isRefusal(String, String)}
 * (setting {@code OrchestratorResult.refusalDetected} truthfully) and
 * {@code AssistiveModeService} routes its single-shot completions through
 * {@link #isRefusal(CompletionResponse)} / {@link #handleRefusal(CompletionResponse)}.
 */
@Service
public class SafetyHandler {

    private static final Logger log = LoggerFactory.getLogger(SafetyHandler.class);

    /**
     * Common refusal patterns found in LLM responses.
     */
    private static final List<String> REFUSAL_PATTERNS = List.of(
            "i cannot",
            "i can't",
            "i'm unable to",
            "i am unable to",
            "i must decline",
            "i'm not able to",
            "i am not able to",
            "as an ai",
            "i apologize, but i cannot",
            "i'm sorry, but i cannot"
    );

    private final LlmProperties llmProperties;

    public SafetyHandler(LlmProperties llmProperties) {
        this.llmProperties = llmProperties;
    }

    /**
     * Handle a potential refusal in the LLM response. If the response indicates
     * a refusal, returns the configured fallback message; otherwise returns the
     * original response content.
     *
     * @param response the completion response to check
     * @return the fallback message if a refusal was detected, or the original content
     */
    public String handleRefusal(CompletionResponse response) {
        if (isRefusal(response)) {
            log.warn("LLM refusal detected [stopReason={}, contentPreview={}]",
                    response.stopReason(),
                    response.content() != null
                            ? response.content().substring(0, Math.min(100, response.content().length()))
                            : "null");
            return fallbackMessage();
        }
        return response.content();
    }

    /**
     * Check whether a completion response indicates a refusal by examining the
     * stop reason and content for known refusal patterns.
     *
     * @param response the completion response to check
     * @return true if the response appears to be a refusal
     */
    public boolean isRefusal(CompletionResponse response) {
        return isRefusal(response.stopReason(), response.content());
    }

    /**
     * Stop-reason + content overload for callers that hold a tool-calling or
     * streaming response rather than a {@link CompletionResponse} (e.g. the
     * orchestrator's final text).
     *
     * @param stopReason the provider stop reason (nullable)
     * @param content    the response text (nullable)
     * @return true if the response appears to be a refusal
     */
    public boolean isRefusal(String stopReason, String content) {
        // Check stop reason
        if ("refusal".equalsIgnoreCase(stopReason)) {
            return true;
        }

        // Check content for refusal patterns
        if (content != null) {
            String lowerContent = content.toLowerCase().trim();
            for (String pattern : REFUSAL_PATTERNS) {
                if (lowerContent.startsWith(pattern)) {
                    return true;
                }
            }
        }

        return false;
    }

    /** The configured user-facing fallback message for refused requests. */
    public String fallbackMessage() {
        return llmProperties.getSafety().getRefusalFallbackMessage();
    }
}
