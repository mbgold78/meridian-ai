package com.meridian.substrate.engine.contract.gateway;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * The tiny predicate DSL (engine-spec contracts.md §4.4; ADR D9) — JSON,
 * evaluated in Java, NEVER SQL-generated. Shared by clause guards (gateway +
 * {@code check_action} advisory) and decline-trigger arming (3b sweep).
 *
 * <p>Grammar: combinators {@code all} / {@code any} / {@code not}; leaves
 * {@code {"field": <dot-path>, <op>: <scalar>}} with ops
 * {@code eq|neq|gt|gte|lt|lte}, and the metric leaf
 * {@code {"metric": "<metricKey>", <op>: <number>}} resolved through the
 * caller-supplied {@link MetricResolver} (latest complete-period value from
 * {@code contract_metric_samples}).
 *
 * <p>Tri-state semantics (§4.4): unknown node shape, unknown op, or an
 * unresolvable field/metric ⇒ {@link Result#INDETERMINATE} — the guard does
 * not match (gateway) / the trigger does not arm (scheduler), and the caller
 * MUST record that an unevaluable predicate was skipped (never silent).
 * Anything richer (temporal windows, {@code sustainedFor}, cross-entity
 * joins) is the {@code GuardPredicate} SPI extension point (spec §15 #6) —
 * do NOT extend this DSL ad hoc.
 */
public final class TinyDslEvaluator {

    /** Tri-state predicate outcome. */
    public enum Result {
        MATCH, NO_MATCH, INDETERMINATE;

        Result negate() {
            return switch (this) {
                case MATCH -> NO_MATCH;
                case NO_MATCH -> MATCH;
                case INDETERMINATE -> INDETERMINATE;
            };
        }
    }

    /** Resolves a metric leaf; empty ⇒ INDETERMINATE (e.g. no complete-period sample). */
    @FunctionalInterface
    public interface MetricResolver {
        Optional<Double> resolve(String metricKey);
    }

    private static final Map<String, Integer> OPS = Map.of(
            "eq", 0, "neq", 0, "gt", 0, "gte", 0, "lt", 0, "lte", 0);

    private TinyDslEvaluator() {
    }

    /**
     * Evaluates {@code predicate} against the evaluation document. Field
     * dot-paths resolve into the document root — gateway callers build
     * {@code {"payload": <payloadSummary>, "target": {"entityType":…,
     * "entityId":…}}} so the spec's {@code payload.scope} paths resolve
     * naturally. A null predicate is the caller's "always matches" case and
     * must be short-circuited BEFORE calling here (this method treats it as
     * INDETERMINATE defensively).
     */
    public static Result evaluate(JsonNode predicate, JsonNode document, MetricResolver metrics) {
        if (predicate == null || predicate.isNull() || !predicate.isObject()) {
            return Result.INDETERMINATE;
        }
        // Combinators — exactly one combinator key, nothing else.
        if (predicate.has("all") || predicate.has("any") || predicate.has("not")) {
            if (predicate.size() != 1) {
                return Result.INDETERMINATE;
            }
            if (predicate.has("not")) {
                return evaluate(predicate.get("not"), document, metrics).negate();
            }
            boolean isAll = predicate.has("all");
            JsonNode children = isAll ? predicate.get("all") : predicate.get("any");
            if (children == null || !children.isArray() || children.isEmpty()) {
                return Result.INDETERMINATE;
            }
            boolean indeterminate = false;
            for (JsonNode child : children) {
                Result r = evaluate(child, document, metrics);
                if (isAll && r == Result.NO_MATCH) {
                    return Result.NO_MATCH;          // definitively false regardless of siblings
                }
                if (!isAll && r == Result.MATCH) {
                    return Result.MATCH;             // definitively true regardless of siblings
                }
                indeterminate |= r == Result.INDETERMINATE;
            }
            if (indeterminate) {
                return Result.INDETERMINATE;
            }
            return isAll ? Result.MATCH : Result.NO_MATCH;
        }
        return evaluateLeaf(predicate, document, metrics);
    }

    // ---------------------------------------------------------------- leaves

    private static Result evaluateLeaf(JsonNode leaf, JsonNode document, MetricResolver metrics) {
        boolean isField = leaf.hasNonNull("field");
        boolean isMetric = leaf.hasNonNull("metric");
        if (isField == isMetric) {                   // neither, or both ⇒ unknown node
            return Result.INDETERMINATE;
        }
        String op = singleOp(leaf);
        if (op == null) {
            return Result.INDETERMINATE;
        }
        JsonNode expected = leaf.get(op);
        if (expected == null || !expected.isValueNode() || expected.isNull()) {
            return Result.INDETERMINATE;
        }

        if (isMetric) {
            if (metrics == null || !expected.isNumber()) {
                return Result.INDETERMINATE;
            }
            Optional<Double> value = metrics.resolve(leaf.get("metric").asText());
            return value.map(v -> compareNumeric(v, expected.asDouble(), op))
                    .orElse(Result.INDETERMINATE);
        }

        JsonNode actual = resolvePath(document, leaf.get("field").asText());
        if (actual == null || actual.isNull() || actual.isMissingNode()
                || actual.isContainerNode()) {
            return Result.INDETERMINATE;             // unresolvable field — never silent upstream
        }
        if (actual.isNumber() && expected.isNumber()) {
            return compareNumeric(actual.asDouble(), expected.asDouble(), op);
        }
        if (op.equals("eq") || op.equals("neq")) {
            boolean equal = actual.asText().equals(expected.asText());
            return (op.equals("eq") == equal) ? Result.MATCH : Result.NO_MATCH;
        }
        return Result.INDETERMINATE;                 // ordering ops need numbers
    }

    /** The leaf's single comparator key; null if zero or several, or unknown extras present. */
    private static String singleOp(JsonNode leaf) {
        String op = null;
        for (Iterator<String> it = leaf.fieldNames(); it.hasNext(); ) {
            String name = it.next();
            if (name.equals("field") || name.equals("metric")) {
                continue;
            }
            if (!OPS.containsKey(name) || op != null) {
                return null;                         // unknown key or second comparator
            }
            op = name;
        }
        return op;
    }

    private static Result compareNumeric(double actual, double expected, String op) {
        boolean r = switch (op) {
            case "eq" -> actual == expected;
            case "neq" -> actual != expected;
            case "gt" -> actual > expected;
            case "gte" -> actual >= expected;
            case "lt" -> actual < expected;
            case "lte" -> actual <= expected;
            default -> throw new IllegalStateException("unreachable op " + op);
        };
        return r ? Result.MATCH : Result.NO_MATCH;
    }

    /** Dot-path resolution into the evaluation document; null when any hop is missing. */
    private static JsonNode resolvePath(JsonNode document, String dotPath) {
        if (document == null || dotPath == null || dotPath.isBlank()) {
            return null;
        }
        JsonNode current = document;
        for (String hop : dotPath.split("\\.")) {
            if (current == null || !current.isObject()) {
                return null;
            }
            current = current.get(hop);
        }
        return current;
    }
}
