package com.meridian.substrate.engine.ai.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.ai.CompletionChunk;
import com.meridian.substrate.common.ai.CompletionRequest;
import com.meridian.substrate.common.ai.CompletionResponse;
import com.meridian.substrate.common.ai.ContentBlock;
import com.meridian.substrate.common.ai.LlmClient;
import com.meridian.substrate.common.ai.TokenUsage;
import com.meridian.substrate.common.ai.ToolCallRequest;
import com.meridian.substrate.common.ai.ToolCallResponse;
import com.meridian.substrate.common.ai.config.LlmProperties;
import com.meridian.substrate.engine.ai.cost.CostTracker;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the runnable eval surface: case loading from {@code classpath:eval/},
 * the {@code mode: "tools"} branch (tool-calling regression against the mock
 * provider semantics), CostTracker wiring, and the EvalRunner exit-code
 * contract (incl. zero-yield HARD FAIL — no-silent-zero-yield norm).
 */
class PromptRegressionHarnessTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final LlmProperties properties = newProperties();

    private static LlmProperties newProperties() {
        LlmProperties props = new LlmProperties();
        props.getAnthropic().setModel("test-model");
        props.getAnthropic().setMaxTokens(256);
        return props;
    }

    @Test
    void runRegressionSuite_loadsShippedCases_andPassesAgainstMockLikeResponses() {
        // Responses mirror MockAnthropicClient's fixed texts — the shipped eval
        // cases are written to pass against the mock provider.
        FakeLlmClient fake = new FakeLlmClient(
                "This is a mock response from MERIDIAN.",
                "Mock tool-calling response from MERIDIAN. No tools were actually invoked.");
        CostTracker costTracker = new CostTracker(properties);
        PromptRegressionHarness harness =
                new PromptRegressionHarness(fake, properties, costTracker, mapper);

        List<EvalResult> results = harness.runRegressionSuite();

        assertThat(results)
                .as("the shipped eval suite must contain more than the single homepoc placeholder")
                .hasSizeGreaterThanOrEqualTo(5);
        assertThat(results).allMatch(EvalResult::passed,
                "every shipped case must pass against mock-provider semantics");

        // Tool-mode cases routed through completeWithTools with their inline tools.
        assertThat(fake.toolRequests).isNotEmpty();
        ToolCallRequest toolsCase = fake.toolRequests.stream()
                .filter(r -> !r.tools().isEmpty())
                .findFirst()
                .orElseThrow();
        assertThat(toolsCase.tools().get(0).name()).isEqualTo("find_systems");

        // CostTracker wiring: one recordUsage per executed case.
        assertThat(costTracker.getSummary().totalCalls()).isEqualTo(results.size());
    }

    @Test
    void failingPattern_reportsFailureReason() {
        FakeLlmClient fake = new FakeLlmClient("zzz nothing relevant zzz", "zzz");
        PromptRegressionHarness harness = new PromptRegressionHarness(
                fake, properties, new CostTracker(properties), mapper);

        EvalResult result = harness.runSingleTest("eval-complete-001-analyst-identity");

        assertThat(result.passed()).isFalse();
        assertThat(result.failureReason()).contains("did not match pattern");
        assertThat(result.actualOutput()).isEqualTo("zzz nothing relevant zzz");
    }

    @Test
    void unknownTestCaseId_failsExplicitly() {
        FakeLlmClient fake = new FakeLlmClient("x", "x");
        PromptRegressionHarness harness = new PromptRegressionHarness(
                fake, properties, new CostTracker(properties), mapper);

        EvalResult result = harness.runSingleTest("no-such-case");

        assertThat(result.passed()).isFalse();
        assertThat(result.failureReason()).contains("Test case not found");
    }

    @Test
    void exitCodes_followTheDocumentedContract() {
        EvalResult pass = new EvalResult("a", true, "in", "p", "out", null);
        EvalResult fail = new EvalResult("b", false, "in", "p", "out", "nope");

        assertThat(EvalRunner.reportAndComputeExitCode(List.of(pass, pass)))
                .isEqualTo(EvalRunner.EXIT_PASS);
        assertThat(EvalRunner.reportAndComputeExitCode(List.of(pass, fail)))
                .isEqualTo(EvalRunner.EXIT_FAILURES);
        assertThat(EvalRunner.reportAndComputeExitCode(List.of()))
                .as("zero eval cases is a HARD FAIL with a distinct exit code, never a pass")
                .isEqualTo(EvalRunner.EXIT_ZERO_YIELD);
    }

    /** Fake LlmClient with fixed complete/tools texts; records tool requests. */
    static class FakeLlmClient implements LlmClient {

        final List<ToolCallRequest> toolRequests = new ArrayList<>();
        private final String completeText;
        private final String toolsText;

        FakeLlmClient(String completeText, String toolsText) {
            this.completeText = completeText;
            this.toolsText = toolsText;
        }

        @Override
        public CompletionResponse complete(CompletionRequest request) {
            return new CompletionResponse("id-" + UUID.randomUUID().toString().substring(0, 8),
                    completeText, "fake-model", "end_turn", new TokenUsage(10, 5));
        }

        @Override
        public Flux<CompletionChunk> completeStream(CompletionRequest request) {
            throw new UnsupportedOperationException("not used by the harness");
        }

        @Override
        public ToolCallResponse completeWithTools(ToolCallRequest request) {
            toolRequests.add(request);
            return new ToolCallResponse("id-" + UUID.randomUUID().toString().substring(0, 8),
                    "fake-model", "end_turn",
                    List.of(ContentBlock.text(toolsText)),
                    new TokenUsage(12, 6));
        }
    }
}
