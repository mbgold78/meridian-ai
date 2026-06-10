package com.meridian.substrate.engine.ai.eval;

/**
 * Result of running a single prompt regression test case against the LLM.
 *
 * @param testCaseId      the unique identifier of the test case
 * @param passed          true if the actual output matched the expected pattern
 * @param input           the input prompt sent to the LLM
 * @param expectedPattern the expected pattern or substring in the output
 * @param actualOutput    the actual output received from the LLM
 * @param failureReason   explanation of why the test failed, or null if it passed
 */
public record EvalResult(
    String testCaseId,
    boolean passed,
    String input,
    String expectedPattern,
    String actualOutput,
    String failureReason
) {}
