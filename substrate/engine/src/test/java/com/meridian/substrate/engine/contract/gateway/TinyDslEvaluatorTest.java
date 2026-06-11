package com.meridian.substrate.engine.contract.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static com.meridian.substrate.engine.contract.gateway.TinyDslEvaluator.Result.INDETERMINATE;
import static com.meridian.substrate.engine.contract.gateway.TinyDslEvaluator.Result.MATCH;
import static com.meridian.substrate.engine.contract.gateway.TinyDslEvaluator.Result.NO_MATCH;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * §4.4 TinyDsl table tests (engine-spec contracts.md §14.3): all comparators,
 * combinators, unknown-node ⇒ INDETERMINATE-not-match, dot-path resolution,
 * metric leaf. The INDETERMINATE outcomes here are exactly the cases the
 * caller must flag (never silent) — guard does not match / trigger does not
 * arm.
 */
class TinyDslEvaluatorTest {

    private final ObjectMapper json = new ObjectMapper();

    private JsonNode doc() {
        try {
            return json.readTree("""
                    {"payload": {"scope": "hipaa-attestation-forms", "formCount": 38,
                                 "nested": {"deep": "v"}},
                     "target": {"entityType": "PROCESS", "entityId": "p-1"}}""");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private TinyDslEvaluator.Result eval(String predicate) {
        return eval(predicate, key -> Optional.empty());
    }

    private TinyDslEvaluator.Result eval(String predicate, TinyDslEvaluator.MetricResolver metrics) {
        try {
            return TinyDslEvaluator.evaluate(json.readTree(predicate), doc(), metrics);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------ comparators

    @Test
    void allComparators() {
        assertThat(eval("""
                {"field":"payload.scope","eq":"hipaa-attestation-forms"}""")).isEqualTo(MATCH);
        assertThat(eval("""
                {"field":"payload.scope","neq":"hipaa-attestation-forms"}""")).isEqualTo(NO_MATCH);
        assertThat(eval("""
                {"field":"payload.formCount","gt":37}""")).isEqualTo(MATCH);
        assertThat(eval("""
                {"field":"payload.formCount","gt":38}""")).isEqualTo(NO_MATCH);
        assertThat(eval("""
                {"field":"payload.formCount","gte":38}""")).isEqualTo(MATCH);
        assertThat(eval("""
                {"field":"payload.formCount","lt":40}""")).isEqualTo(MATCH);
        assertThat(eval("""
                {"field":"payload.formCount","lte":38}""")).isEqualTo(MATCH);
        assertThat(eval("""
                {"field":"payload.formCount","lte":37}""")).isEqualTo(NO_MATCH);
        assertThat(eval("""
                {"field":"payload.formCount","eq":38}""")).isEqualTo(MATCH);
        assertThat(eval("""
                {"field":"payload.formCount","neq":38}""")).isEqualTo(NO_MATCH);
    }

    @Test
    void orderingComparatorsNeedNumbers() {
        assertThat(eval("""
                {"field":"payload.scope","gt":3}""")).isEqualTo(INDETERMINATE);
    }

    // ------------------------------------------------------------ combinators

    @Test
    void specExampleAllCombinator() {
        // The literal §4.4 example.
        assertThat(eval("""
                {"all": [ {"field": "payload.scope", "eq": "hipaa-attestation-forms"},
                          {"field": "payload.formCount", "lte": 40} ]}""")).isEqualTo(MATCH);
    }

    @Test
    void combinatorTruthTables() {
        assertThat(eval("""
                {"all":[{"field":"payload.formCount","gt":37},
                        {"field":"payload.formCount","gt":100}]}""")).isEqualTo(NO_MATCH);
        assertThat(eval("""
                {"any":[{"field":"payload.formCount","gt":100},
                        {"field":"payload.scope","eq":"hipaa-attestation-forms"}]}""")).isEqualTo(MATCH);
        assertThat(eval("""
                {"any":[{"field":"payload.formCount","gt":100},
                        {"field":"payload.scope","eq":"other"}]}""")).isEqualTo(NO_MATCH);
        assertThat(eval("""
                {"not":{"field":"payload.scope","eq":"other"}}""")).isEqualTo(MATCH);
        assertThat(eval("""
                {"not":{"field":"payload.scope","eq":"hipaa-attestation-forms"}}""")).isEqualTo(NO_MATCH);
        // Nesting.
        assertThat(eval("""
                {"all":[{"any":[{"field":"payload.formCount","lt":10},
                                {"field":"payload.nested.deep","eq":"v"}]},
                        {"not":{"field":"target.entityType","eq":"SYSTEM"}}]}""")).isEqualTo(MATCH);
    }

    @Test
    void indeterminatePropagation() {
        // A definitive false dominates an indeterminate sibling in `all`…
        assertThat(eval("""
                {"all":[{"field":"payload.missing","eq":1},
                        {"field":"payload.formCount","gt":100}]}""")).isEqualTo(NO_MATCH);
        // …but an indeterminate child blocks a confident MATCH.
        assertThat(eval("""
                {"all":[{"field":"payload.missing","eq":1},
                        {"field":"payload.formCount","gt":1}]}""")).isEqualTo(INDETERMINATE);
        // `any`: a definitive true dominates; otherwise indeterminate sticks.
        assertThat(eval("""
                {"any":[{"field":"payload.missing","eq":1},
                        {"field":"payload.formCount","gt":1}]}""")).isEqualTo(MATCH);
        assertThat(eval("""
                {"any":[{"field":"payload.missing","eq":1},
                        {"field":"payload.formCount","gt":100}]}""")).isEqualTo(INDETERMINATE);
        // `not` never launders indeterminate into a decision.
        assertThat(eval("""
                {"not":{"field":"payload.missing","eq":1}}""")).isEqualTo(INDETERMINATE);
    }

    // ----------------------------------------------- unknown nodes and fields

    @Test
    void unknownNodeShapesAreIndeterminateNeverMatch() {
        assertThat(eval("""
                {"sustainedFor":{"quarters":2}}""")).isEqualTo(INDETERMINATE);  // the ADR D9 example
        assertThat(eval("""
                {"field":"payload.formCount","between":[1,2]}""")).isEqualTo(INDETERMINATE);
        assertThat(eval("""
                {"field":"payload.formCount"}""")).isEqualTo(INDETERMINATE);    // no comparator
        assertThat(eval("""
                {"field":"payload.formCount","gt":1,"lt":40}""")).isEqualTo(INDETERMINATE); // two comparators
        assertThat(eval("""
                {"all":[{"field":"payload.formCount","gt":1}],"any":[]}""")).isEqualTo(INDETERMINATE);
        assertThat(eval("[]")).isEqualTo(INDETERMINATE);
        assertThat(eval("\"gt\"")).isEqualTo(INDETERMINATE);
        assertThat(eval("{}")).isEqualTo(INDETERMINATE);
        assertThat(eval("""
                {"all":[]}""")).isEqualTo(INDETERMINATE);
    }

    @Test
    void unresolvableFieldsAreIndeterminate() {
        assertThat(eval("""
                {"field":"payload.absent","eq":1}""")).isEqualTo(INDETERMINATE);
        assertThat(eval("""
                {"field":"payload.nested","eq":1}""")).isEqualTo(INDETERMINATE);  // container, not scalar
        assertThat(eval("""
                {"field":"","eq":1}""")).isEqualTo(INDETERMINATE);
        assertThat(eval("""
                {"field":"payload.scope.deeper","eq":1}""")).isEqualTo(INDETERMINATE);
    }

    @Test
    void dotPathsResolveIntoPayloadAndTarget() {
        assertThat(eval("""
                {"field":"payload.nested.deep","eq":"v"}""")).isEqualTo(MATCH);
        assertThat(eval("""
                {"field":"target.entityType","eq":"PROCESS"}""")).isEqualTo(MATCH);
        assertThat(eval("""
                {"field":"target.entityId","neq":"p-2"}""")).isEqualTo(MATCH);
    }

    // -------------------------------------------------------------- metric leaf

    @Test
    void metricLeaf() {
        TinyDslEvaluator.MetricResolver accuracy88 =
                key -> "accuracy".equals(key) ? Optional.of(0.88) : Optional.empty();
        // The fi-shared-pre-fill-agent arming predicate (fixture vector 2).
        assertThat(eval("""
                {"metric":"accuracy","lt":0.90}""", accuracy88)).isEqualTo(MATCH);
        assertThat(eval("""
                {"metric":"accuracy","gte":0.90}""", accuracy88)).isEqualTo(NO_MATCH);
        // Unresolvable metric (no complete-period sample) ⇒ INDETERMINATE.
        assertThat(eval("""
                {"metric":"utilization_share","gt":0.30}""", accuracy88)).isEqualTo(INDETERMINATE);
        // Metric leaves compare numbers only.
        assertThat(eval("""
                {"metric":"accuracy","eq":"high"}""", accuracy88)).isEqualTo(INDETERMINATE);
        // A leaf claiming to be both field and metric is an unknown node.
        assertThat(eval("""
                {"field":"payload.formCount","metric":"accuracy","gt":1}""", accuracy88))
                .isEqualTo(INDETERMINATE);
    }

    @Test
    void nullPredicateIsDefensivelyIndeterminate() {
        // Callers short-circuit predicate-null as "always matches" BEFORE the
        // evaluator (§4.3); the evaluator itself never invents a match.
        assertThat(TinyDslEvaluator.evaluate(null, doc(), k -> Optional.empty()))
                .isEqualTo(INDETERMINATE);
        assertThat(TinyDslEvaluator.evaluate(
                com.fasterxml.jackson.databind.node.NullNode.getInstance(), doc(),
                k -> Optional.empty()))
                .isEqualTo(INDETERMINATE);
    }
}
