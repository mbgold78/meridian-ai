package com.meridian.substrate.engine.ai.eval;

import com.meridian.substrate.engine.EngineTestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Engine-spec diagnostic.md §13 — the expanded eval suite runs GREEN through
 * the real {@link PromptRegressionHarness} under the mock provider (the
 * deterministic CI lane of the documented EvalRunner command), AND the
 * pre-existing eval expectations are unperturbed by the find_evidence
 * registration + prompt extension (the pinned-surface non-perturbation
 * proof at suite level):
 *
 * <ul>
 *   <li>the five pre-§13 cases still load and still pass byte-identically;</li>
 *   <li>the validative JSON-contract cases assert real
 *       {@code CandidateFilter} outcomes (well-formed / prose-wrapped /
 *       URGENT / missing-field / fabricated-evidence / title+findingType
 *       round-trip / invalid-findingType-kept / bad-type);</li>
 *   <li>the generative allowlist cases assert grounding + allowlist outcomes
 *       against the service-owned allowlist;</li>
 *   <li>the tool-grounding case drives the FULL pinned validative pipeline:
 *       MockAnthropicClient's {@code grounded-content} script issues a real
 *       {@code find_evidence} call and the grounded CONTENT candidate
 *       survives while its ungrounded / invalid-severity siblings are
 *       filtered (ADR-004 D6 regression pin).</li>
 * </ul>
 */
// Deliberately NOT @Transactional: the find_evidence tool executes on the
// dispatcher pool thread (its own transaction), so the evaluator's seeded
// evidence must be COMMITTED to be visible — exactly the EvalRunner runtime
// shape. The engine test context is a throwaway create-drop H2.
@SpringBootTest(classes = EngineTestApplication.class, properties = {
        "substrate.eval.enabled=true"
})
@ActiveProfiles("test")
class DiagnosticEvalSuiteTest {

    private static final List<String> PRE_EXISTING_CASE_IDS = List.of(
            "eval-complete-001-analyst-identity",
            "eval-complete-002-systems-question",
            "eval-complete-003-grounding-discipline",
            "eval-tools-001-toolcall-roundtrip",
            "eval-tools-002-no-tools-defined");

    @Autowired PromptRegressionHarness harness;

    @Test
    void expandedSuite_passesUnderMockProvider_andPreExistingCasesAreUnperturbed() {
        List<EvalResult> results = harness.runRegressionSuite();

        // 5 pre-existing + 9 validative + 3 generative
        assertThat(results).hasSizeGreaterThanOrEqualTo(17);

        assertThat(results)
                .as("every case passes under the mock provider — the EvalRunner "
                        + "command's exit-0 contract")
                .allSatisfy(result -> assertThat(result.passed())
                        .as("case %s: %s [actual: %s]", result.testCaseId(),
                                result.failureReason(), result.actualOutput())
                        .isTrue());

        // Pinned-surface guard: the pre-existing expectations still load + pass.
        List<String> ids = results.stream().map(EvalResult::testCaseId).toList();
        assertThat(ids).containsAll(PRE_EXISTING_CASE_IDS);

        // The §13 diagnostic cases actually ran (not silently skipped).
        assertThat(ids).contains(
                "diag-val-001-wellformed-structural-kept",
                "diag-val-005-content-fabricated-evidence-filtered",
                "diag-val-009-grounded-content-survives-via-find-evidence",
                "diag-gen-001-extra-reasoning-field-dropped",
                "diag-gen-003-ungrounded-proposal-filtered");

        // D6 pin: the tool-grounding case proves a REAL find_evidence round trip.
        EvalResult grounded = results.stream()
                .filter(r -> r.testCaseId().equals(
                        "diag-val-009-grounded-content-survives-via-find-evidence"))
                .findFirst().orElseThrow();
        assertThat(grounded.actualOutput()).contains("find_evidence");
        assertThat(grounded.actualOutput()).contains("KEPT");
    }
}
