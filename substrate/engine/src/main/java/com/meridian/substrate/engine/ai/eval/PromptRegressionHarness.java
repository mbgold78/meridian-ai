package com.meridian.substrate.engine.ai.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.ai.CompletionRequest;
import com.meridian.substrate.common.ai.CompletionResponse;
import com.meridian.substrate.common.ai.ContentBlock;
import com.meridian.substrate.common.ai.LlmClient;
import com.meridian.substrate.common.ai.ToolCallRequest;
import com.meridian.substrate.common.ai.ToolCallResponse;
import com.meridian.substrate.common.ai.config.LlmProperties;
import com.meridian.substrate.engine.ai.cost.CostTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Prompt regression test harness for evaluating LLM responses against expected patterns.
 * <p>
 * Loads test cases from {@code classpath:eval/} as JSON files. Each test case defines
 * an input prompt and an expected output pattern (regex). The harness runs each test
 * against the LLM and checks for pattern matches.
 * <p>
 * Disabled by default; enable via {@code substrate.eval.enabled=true} in configuration.
 *
 * <p><b>Port notes (port-map §2.3 / S17):</b> homepoc shipped this with zero
 * callers. In substrate it is runnable via {@link EvalRunner} (see that class
 * for the documented command). Test cases now support a {@code mode} field:
 * {@code "complete"} (default, single-shot) or {@code "tools"} (routes through
 * {@link LlmClient#completeWithTools} with the case's inline tool definitions —
 * the tool-calling regression seam against {@code MockAnthropicClient}).
 * Token usage is recorded through {@link CostTracker} like every other engine
 * LLM call site.
 *
 * <p><b>Diagnostic modes (engine-spec diagnostic.md §13):</b> {@code mode:
 * "validative" | "generative"} cases assert structured parseability + filter
 * outcomes via {@link DiagnosticEvalEvaluator} (one chokepoint — the real
 * {@code CandidateFilter} / pinned mode services, never a re-derived filter).
 * Additive case fields: {@code candidatePayload} (stands in for the model's
 * final text — deterministic, no LLM call), {@code mockScript} (arms the
 * MockAnthropicClient diagnostic script for full-pipeline cases),
 * {@code seedEvidence} (deterministic grounding; <code>{{evidence:i}}</code>
 * placeholders), {@code scope}, {@code entityType}, and
 * {@code expectedOutcomes} (multiset of filter outcomes). {@code
 * expectedPattern} additionally matches against the structured outcome
 * summary. When constructed WITHOUT an evaluator (compatibility constructor —
 * plain unit usage), diagnostic-mode cases are skipped with a warning; the
 * pre-existing complete/tools cases run byte-identically (the §13
 * pinned-surface non-perturbation guard).
 */
@Service
@ConditionalOnProperty(name = "substrate.eval.enabled", havingValue = "true", matchIfMissing = false)
public class PromptRegressionHarness {

    private static final Logger log = LoggerFactory.getLogger(PromptRegressionHarness.class);

    static final String MODE_COMPLETE = "complete";
    static final String MODE_TOOLS = "tools";
    static final String MODE_VALIDATIVE = "validative";
    static final String MODE_GENERATIVE = "generative";

    private final LlmClient llmClient;
    private final LlmProperties llmProperties;
    private final CostTracker costTracker;
    private final ObjectMapper objectMapper;
    private final DiagnosticEvalEvaluator diagnosticEvaluator;

    @org.springframework.beans.factory.annotation.Autowired
    public PromptRegressionHarness(LlmClient llmClient,
                                   LlmProperties llmProperties,
                                   CostTracker costTracker,
                                   ObjectMapper objectMapper,
                                   DiagnosticEvalEvaluator diagnosticEvaluator) {
        this.llmClient = llmClient;
        this.llmProperties = llmProperties;
        this.costTracker = costTracker;
        this.objectMapper = objectMapper;
        this.diagnosticEvaluator = diagnosticEvaluator;
    }

    /**
     * Compatibility constructor (pre-§13 arity): no diagnostic evaluator —
     * {@code validative}/{@code generative} cases are skipped with a warning.
     */
    public PromptRegressionHarness(LlmClient llmClient,
                                   LlmProperties llmProperties,
                                   CostTracker costTracker,
                                   ObjectMapper objectMapper) {
        this(llmClient, llmProperties, costTracker, objectMapper, null);
    }

    /**
     * Run the full regression suite by loading all test cases from the eval directory
     * and executing each one against the LLM.
     *
     * @return list of eval results, one per test case
     */
    public List<EvalResult> runRegressionSuite() {
        log.info("Starting prompt regression suite");

        List<TestCase> testCases = loadTestCases();
        if (testCases.isEmpty()) {
            log.warn("No test cases found in classpath:eval/");
            return List.of();
        }

        List<EvalResult> results = new ArrayList<>();
        for (TestCase testCase : testCases) {
            if (isDiagnosticMode(testCase) && diagnosticEvaluator == null) {
                log.warn("Skipping diagnostic-mode eval case [id={}, mode={}] — no "
                        + "DiagnosticEvalEvaluator wired (compatibility-constructor usage)",
                        testCase.id, testCase.mode);
                continue;
            }
            results.add(runSingleTestCase(testCase));
        }

        long passed = results.stream().filter(EvalResult::passed).count();
        log.info("Prompt regression suite complete [{}/{} passed]", passed, results.size());

        return results;
    }

    /**
     * Run a single test case by its identifier.
     *
     * @param testCaseId the unique identifier of the test case to run
     * @return the eval result for the test case
     */
    public EvalResult runSingleTest(String testCaseId) {
        log.info("Running single test case [testCaseId={}]", testCaseId);

        List<TestCase> testCases = loadTestCases();
        TestCase testCase = testCases.stream()
                .filter(tc -> testCaseId.equals(tc.id))
                .findFirst()
                .orElse(null);

        if (testCase == null) {
            return new EvalResult(testCaseId, false, "", "", "",
                    "Test case not found: " + testCaseId);
        }

        return runSingleTestCase(testCase);
    }

    /**
     * Execute a single test case against the LLM and check the result.
     */
    private EvalResult runSingleTestCase(TestCase testCase) {
        log.debug("Executing test case [id={}, mode={}]", testCase.id, testCase.mode);

        try {
            if (isDiagnosticMode(testCase)) {
                if (diagnosticEvaluator == null) {
                    return new EvalResult(testCase.id, false, testCase.input,
                            testCase.expectedPattern, "",
                            "diagnostic-mode case but no DiagnosticEvalEvaluator wired");
                }
                return runDiagnosticCase(testCase);
            }

            String actualOutput = MODE_TOOLS.equalsIgnoreCase(testCase.mode)
                    ? executeToolsCase(testCase)
                    : executeCompleteCase(testCase);

            // Check if the output matches the expected pattern
            boolean passed;
            String failureReason = null;

            if (testCase.expectedPattern != null) {
                Pattern pattern = Pattern.compile(testCase.expectedPattern, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
                passed = pattern.matcher(actualOutput).find();
                if (!passed) {
                    failureReason = String.format("Output did not match pattern '%s'", testCase.expectedPattern);
                }
            } else {
                passed = true; // No pattern to check = pass
            }

            return new EvalResult(testCase.id, passed, testCase.input,
                    testCase.expectedPattern, actualOutput, failureReason);

        } catch (Exception e) {
            log.error("Test case failed with exception [id={}]: {}", testCase.id, e.getMessage(), e);
            return new EvalResult(testCase.id, false, testCase.input,
                    testCase.expectedPattern, "", "Exception: " + e.getMessage());
        }
    }

    private static boolean isDiagnosticMode(TestCase testCase) {
        return MODE_VALIDATIVE.equalsIgnoreCase(testCase.mode)
                || MODE_GENERATIVE.equalsIgnoreCase(testCase.mode);
    }

    /**
     * §13 diagnostic case: structured parseability + filter outcomes. Passes
     * iff the (multiset-normalized) outcomes equal {@code expectedOutcomes}
     * (when declared) AND {@code expectedPattern} (when declared) matches the
     * structured outcome summary.
     */
    private EvalResult runDiagnosticCase(TestCase testCase) {
        DiagnosticEvalEvaluator.Evaluation evaluation = diagnosticEvaluator.evaluate(
                new DiagnosticEvalEvaluator.CaseSpec(
                        testCase.mode,
                        testCase.input,
                        testCase.candidatePayload,
                        testCase.mockScript,
                        testCase.seedEvidence == null ? 0 : testCase.seedEvidence,
                        testCase.scope,
                        testCase.entityType));

        List<String> failures = new ArrayList<>();
        if (testCase.expectedOutcomes != null) {
            List<String> expected = testCase.expectedOutcomes.stream().sorted().toList();
            if (!expected.equals(evaluation.outcomes())) {
                failures.add(String.format("expected outcomes %s but got %s",
                        expected, evaluation.outcomes()));
            }
        }
        if (testCase.expectedPattern != null) {
            Pattern pattern = Pattern.compile(testCase.expectedPattern,
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            if (!pattern.matcher(evaluation.summary()).find()) {
                failures.add(String.format("summary did not match pattern '%s'",
                        testCase.expectedPattern));
            }
        }

        return new EvalResult(testCase.id, failures.isEmpty(), testCase.input,
                testCase.expectedPattern, evaluation.summary(),
                failures.isEmpty() ? null : String.join("; ", failures));
    }

    private String executeCompleteCase(TestCase testCase) {
        String model = llmProperties.getAnthropic().getModel();
        int maxTokens = llmProperties.getAnthropic().getMaxTokens();

        List<CompletionRequest.Message> messages = new ArrayList<>();
        messages.add(CompletionRequest.Message.user(testCase.input));

        CompletionRequest request = CompletionRequest.of(
                model, systemPromptOrDefault(testCase), messages, maxTokens);
        CompletionResponse response = llmClient.complete(request);
        recordUsage(model, response.model(),
                response.usage() != null ? response.usage().inputTokens() : 0,
                response.usage() != null ? response.usage().outputTokens() : 0,
                response.usage() != null);
        return response.content();
    }

    private String executeToolsCase(TestCase testCase) {
        String model = llmProperties.getAnthropic().getModel();
        int maxTokens = llmProperties.getAnthropic().getMaxTokens();

        List<ToolCallRequest.ToolDefinition> tools = new ArrayList<>();
        if (testCase.tools != null) {
            for (TestTool tool : testCase.tools) {
                Map<String, Object> schema = tool.inputSchema != null
                        ? tool.inputSchema
                        : defaultEmptyObjectSchema();
                tools.add(new ToolCallRequest.ToolDefinition(tool.name, tool.description, schema));
            }
        }

        ToolCallRequest request = ToolCallRequest.of(
                model, systemPromptOrDefault(testCase),
                List.of(CompletionRequest.Message.user(testCase.input)),
                tools, maxTokens);
        ToolCallResponse response = llmClient.completeWithTools(request);
        recordUsage(model, response.model(),
                response.usage() != null ? response.usage().inputTokens() : 0,
                response.usage() != null ? response.usage().outputTokens() : 0,
                response.usage() != null);

        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : response.content()) {
            if (block.isText() && block.text() != null) {
                sb.append(block.text());
            } else if (block.isToolUse()) {
                // Surface requested tool calls so patterns can assert on them.
                sb.append("[tool_use:").append(block.toolName()).append("]");
            }
        }
        return sb.toString();
    }

    private void recordUsage(String configuredModel, String responseModel,
                             int inputTokens, int outputTokens, boolean hasUsage) {
        if (hasUsage) {
            costTracker.recordUsage(
                    responseModel != null ? responseModel : configuredModel,
                    inputTokens, outputTokens);
        }
    }

    private String systemPromptOrDefault(TestCase testCase) {
        return testCase.systemPrompt != null
                ? testCase.systemPrompt
                : "You are a helpful assistant.";
    }

    private Map<String, Object> defaultEmptyObjectSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", new HashMap<>());
        return schema;
    }

    /**
     * Load all test cases from the eval directory on the classpath.
     */
    private List<TestCase> loadTestCases() {
        List<TestCase> allTestCases = new ArrayList<>();

        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath*:eval/*.json");

            for (Resource resource : resources) {
                try (InputStream is = resource.getInputStream()) {
                    String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    List<TestCase> cases = objectMapper.readValue(content,
                            new TypeReference<List<TestCase>>() {});
                    allTestCases.addAll(cases);
                    log.debug("Loaded {} test cases from {}", cases.size(), resource.getFilename());
                } catch (Exception e) {
                    log.warn("Failed to load test cases from {}: {}", resource.getFilename(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan eval directory: {}", e.getMessage());
        }

        return allTestCases;
    }

    /**
     * Internal representation of a test case loaded from JSON.
     */
    static class TestCase {
        public String id;
        public String input;
        public String systemPrompt;
        public String expectedPattern;
        /** {@code "complete"} (default), {@code "tools"}, {@code "validative"} or {@code "generative"}. */
        public String mode;
        /** Inline tool definitions for {@code mode: "tools"} cases. */
        public List<TestTool> tools;

        // ---- §13 diagnostic-mode fields (validative | generative) ----
        /** Raw text standing in for the model's final output (no LLM call when present). */
        public String candidatePayload;
        /** MockAnthropicClient diagnostic script to arm for full-pipeline cases. */
        public String mockScript;
        /** Seed N eval-harness Evidence rows; payloads may cite {@code {{evidence:i}}}. */
        public Integer seedEvidence;
        /** Validative scope label recorded in Assertion provenance (default {@code systems}). */
        public String scope;
        /** Generative entity type (default {@code capability}). */
        public String entityType;
        /** Expected filter outcomes, compared as a multiset (order-independent). */
        public List<String> expectedOutcomes;
    }

    /** Inline tool definition carried by a {@code mode: "tools"} test case. */
    static class TestTool {
        public String name;
        public String description;
        public Map<String, Object> inputSchema;
    }
}
