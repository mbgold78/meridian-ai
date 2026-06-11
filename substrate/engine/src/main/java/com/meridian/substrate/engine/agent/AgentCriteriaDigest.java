package com.meridian.substrate.engine.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * {@code criteria_digest} computation (engine-spec agents.md §9.1; ADR-003
 * D5): SHA-256 over the CANONICAL serialization of
 * {@code (deployment_criteria + ordered {gateText, ruleType, predicate,
 * cadence} rows)}, frozen at deploy and anchored in the DEPLOYED hash-chain
 * entry (6b) — post-hoc mandate tampering is detectable.
 *
 * <p>Canonical form (stable — any change is a digest-vector break and must be
 * a reviewed migration):
 * <pre>
 * {"deploymentCriteria":&lt;prose&gt;,
 *  "gates":[{"cadence":…,"gateText":…,"predicate":…,"ruleType":…}, …]}
 * </pre>
 * keys inserted in fixed alphabetical order, gates in their authored
 * {@code sequence_order}, NULLs serialized as JSON null, predicate embedded
 * as its parsed JSON tree (whitespace-insensitive), UTF-8 bytes, lowercase
 * hex output. Pinned by {@code AgentCriteriaDigestTest}.
 */
public final class AgentCriteriaDigest {

    private AgentCriteriaDigest() {
    }

    /** One ordered mandate clause as authored at deploy (spec §9.1). */
    public record GateInput(String gateText, String ruleType, JsonNode predicate, String cadence) {
    }

    public static String compute(String deploymentCriteria, List<GateInput> gates) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        ObjectNode root = f.objectNode();
        root.put("deploymentCriteria", deploymentCriteria);
        ArrayNode gateArray = root.putArray("gates");
        for (GateInput gate : gates) {
            ObjectNode g = gateArray.addObject();
            // fixed alphabetical key order — canonical layout
            if (gate.cadence() == null) {
                g.putNull("cadence");
            } else {
                g.put("cadence", gate.cadence());
            }
            g.put("gateText", gate.gateText());
            if (gate.predicate() == null || gate.predicate().isNull()) {
                g.putNull("predicate");
            } else {
                g.set("predicate", gate.predicate());
            }
            g.put("ruleType", gate.ruleType());
        }
        return sha256Hex(root.toString());
    }

    private static String sha256Hex(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
