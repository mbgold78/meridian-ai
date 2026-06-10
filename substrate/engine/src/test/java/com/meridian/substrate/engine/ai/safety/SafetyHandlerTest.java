package com.meridian.substrate.engine.ai.safety;

import com.meridian.substrate.common.ai.CompletionResponse;
import com.meridian.substrate.common.ai.TokenUsage;
import com.meridian.substrate.common.ai.config.LlmProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SafetyHandlerTest {

    private final LlmProperties properties = new LlmProperties();
    private final SafetyHandler handler = new SafetyHandler(properties);

    private static CompletionResponse response(String content, String stopReason) {
        return new CompletionResponse("id", content, "m", stopReason, new TokenUsage(1, 1));
    }

    @Test
    void detectsRefusalByStopReason() {
        assertThat(handler.isRefusal(response("anything", "refusal"))).isTrue();
        assertThat(handler.isRefusal(response("anything", "REFUSAL"))).isTrue();
    }

    @Test
    void detectsRefusalByLeadingPattern() {
        assertThat(handler.isRefusal(response("I cannot help with that.", "end_turn"))).isTrue();
        assertThat(handler.isRefusal(response("  I'm unable to do this.", "end_turn"))).isTrue();
        assertThat(handler.isRefusal(response("As an AI, I should not.", "end_turn"))).isTrue();
        assertThat(handler.isRefusal(response("I must decline this request.", "end_turn"))).isTrue();
    }

    @Test
    void refusalPatternMustBeAtStart_notMidSentence() {
        assertThat(handler.isRefusal(response(
                "The system reports: users sometimes say 'I cannot log in'.", "end_turn")))
                .isFalse();
    }

    @Test
    void normalResponsePassesThrough() {
        CompletionResponse ok = response("There are 12 systems in the domain.", "end_turn");
        assertThat(handler.isRefusal(ok)).isFalse();
        assertThat(handler.handleRefusal(ok)).isEqualTo("There are 12 systems in the domain.");
    }

    @Test
    void handleRefusal_returnsConfiguredFallback() {
        properties.getSafety().setRefusalFallbackMessage("Custom fallback.");
        CompletionResponse refused = response("I cannot answer that.", "end_turn");
        assertThat(handler.handleRefusal(refused)).isEqualTo("Custom fallback.");
        assertThat(handler.fallbackMessage()).isEqualTo("Custom fallback.");
    }

    @Test
    void nullContentIsNotARefusal_unlessStopReasonSaysSo() {
        assertThat(handler.isRefusal((String) null, null)).isFalse();
        assertThat(handler.isRefusal("refusal", null)).isTrue();
    }
}
