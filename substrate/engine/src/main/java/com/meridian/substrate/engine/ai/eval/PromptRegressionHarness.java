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
 */
@Service
@ConditionalOnProperty(name = "substrate.eval.enabled", havingValue = "true", matchIfMissing = false)
public class PromptRegressionHarness {

    private static final Logger log = LoggerFactory.getLogger(PromptRegressionHarness.class);

    static final String MODE_COMPLETE = "complete";
    static final String MODE_TOOLS = "tools";

    private final LlmClient llmClient;
    private final LlmProperties llmProperties;
    private final CostTracker costTracker;
    private final ObjectMapper objectMapper;

    public PromptRegressionHarness(LlmClient llmClient,
                                   LlmProperties llmProperties,
                                   CostTracker costTracker,
                                   ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.llmProperties = llmProperties;
        this.costTracker = costTracker;
        this.objectMapper = objectMapper;
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
        /** {@code "complete"} (default) or {@code "tools"}. */
        public String mode;
        /** Inline tool definitions for {@code mode: "tools"} cases. */
        public List<TestTool> tools;
    }

    /** Inline tool definition carried by a {@code mode: "tools"} test case. */
    static class TestTool {
        public String name;
        public String description;
        public Map<String, Object> inputSchema;
    }
}
