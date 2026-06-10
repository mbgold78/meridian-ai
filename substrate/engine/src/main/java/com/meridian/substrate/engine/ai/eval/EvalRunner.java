package com.meridian.substrate.engine.ai.eval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Runnable surface for the {@link PromptRegressionHarness} (port-map §2.3 /
 * acceptance-must-be-user-runnable). Activated by {@code substrate.eval.run=true};
 * runs the regression suite at application startup, emits structured event lines,
 * and exits the JVM with a distinct exit code.
 *
 * <p><b>Documented command</b> (mock provider — no API key needed):
 * <pre>
 * gradlew :app:bootRun --args="--substrate.eval.enabled=true --substrate.eval.run=true --substrate.llm.provider=mock"
 * </pre>
 * Substitute {@code --substrate.llm.provider=anthropic} (with {@code ANTHROPIC_API_KEY}
 * set) to evaluate against the real provider.
 *
 * <p><b>Exit codes</b> (no-silent-zero-yield norm — "command exited 0" must mean
 * "the suite ran and passed"):
 * <ul>
 *   <li>{@code 0} — suite ran, every case passed</li>
 *   <li>{@code 2} — suite ran, at least one case failed</li>
 *   <li>{@code 3} — HARD FAIL: zero test cases found (zero-yield)</li>
 *   <li>{@code 4} — HARD FAIL: runner requested but harness disabled
 *       ({@code substrate.eval.enabled} is not {@code true})</li>
 * </ul>
 *
 * <p><b>Structured event lines</b> (one per case + a suite summary, written to the
 * application log):
 * <pre>
 * EVAL_CASE id=&lt;id&gt; status=PASS|FAIL reason=&lt;...&gt;
 * EVAL_SUITE total=&lt;n&gt; passed=&lt;n&gt; failed=&lt;n&gt; status=PASS|FAIL|EMPTY|DISABLED exit=&lt;code&gt;
 * </pre>
 */
@Component
@ConditionalOnProperty(name = "substrate.eval.run", havingValue = "true", matchIfMissing = false)
public class EvalRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

    static final int EXIT_PASS = 0;
    static final int EXIT_FAILURES = 2;
    static final int EXIT_ZERO_YIELD = 3;
    static final int EXIT_HARNESS_DISABLED = 4;

    private final ObjectProvider<PromptRegressionHarness> harnessProvider;
    private final ConfigurableApplicationContext context;

    public EvalRunner(ObjectProvider<PromptRegressionHarness> harnessProvider,
                      ConfigurableApplicationContext context) {
        this.harnessProvider = harnessProvider;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = execute();
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    /**
     * Runs the suite and computes the exit code without terminating the JVM —
     * separated from {@link #run} for testability.
     */
    int execute() {
        PromptRegressionHarness harness = harnessProvider.getIfAvailable();
        if (harness == null) {
            log.error("EVAL_SUITE total=0 passed=0 failed=0 status=DISABLED exit={} "
                    + "hint='set substrate.eval.enabled=true alongside substrate.eval.run=true'",
                    EXIT_HARNESS_DISABLED);
            return EXIT_HARNESS_DISABLED;
        }

        List<EvalResult> results = harness.runRegressionSuite();
        return reportAndComputeExitCode(results);
    }

    /** Emits structured event lines and maps results to the documented exit codes. */
    static int reportAndComputeExitCode(List<EvalResult> results) {
        if (results.isEmpty()) {
            log.error("EVAL_SUITE total=0 passed=0 failed=0 status=EMPTY exit={} "
                    + "hint='zero eval cases found on classpath:eval/ — this is a hard failure, "
                    + "not a pass'", EXIT_ZERO_YIELD);
            return EXIT_ZERO_YIELD;
        }

        long passed = 0;
        long failed = 0;
        for (EvalResult result : results) {
            if (result.passed()) {
                passed++;
                log.info("EVAL_CASE id={} status=PASS", result.testCaseId());
            } else {
                failed++;
                log.error("EVAL_CASE id={} status=FAIL reason='{}' actual='{}'",
                        result.testCaseId(),
                        result.failureReason(),
                        truncate(result.actualOutput()));
            }
        }

        int exitCode = failed > 0 ? EXIT_FAILURES : EXIT_PASS;
        String status = failed > 0 ? "FAIL" : "PASS";
        if (failed > 0) {
            log.error("EVAL_SUITE total={} passed={} failed={} status={} exit={}",
                    results.size(), passed, failed, status, exitCode);
        } else {
            log.info("EVAL_SUITE total={} passed={} failed={} status={} exit={}",
                    results.size(), passed, failed, status, exitCode);
        }
        return exitCode;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }
}
