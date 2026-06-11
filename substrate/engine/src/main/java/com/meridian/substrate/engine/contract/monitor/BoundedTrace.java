package com.meridian.substrate.engine.contract.monitor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;

/**
 * Bounds the persisted monitor-run trace JSON at
 * {@code substrate.contracts.trace.max-bytes} (engine-spec contracts.md §1;
 * ADR D10 evaluation-trace graft): a trace whose UTF-8 serialization exceeds
 * the cap is replaced by a {@code {"truncated": true, "originalBytes": n,
 * "head": "<serialized prefix>"}} marker object that itself fits the cap.
 */
public final class BoundedTrace {

    /** Serialized size of the marker skeleton without the head payload. */
    private static final String TRUNCATED_FIELD = "truncated";
    private static final String ORIGINAL_BYTES_FIELD = "originalBytes";
    private static final String HEAD_FIELD = "head";

    private BoundedTrace() {
    }

    /**
     * Returns {@code trace} unchanged when it fits {@code maxBytes}; otherwise
     * the truncation marker (always ≤ {@code maxBytes}). Null traces pass
     * through (the run row's {@code details} column is nullable).
     */
    public static JsonNode bound(ObjectMapper mapper, JsonNode trace, int maxBytes) {
        if (trace == null || trace.isNull()) {
            return trace;
        }
        byte[] serialized;
        try {
            serialized = mapper.writeValueAsBytes(trace);
        } catch (JsonProcessingException e) {
            ObjectNode marker = mapper.createObjectNode();
            marker.put(TRUNCATED_FIELD, true);
            marker.put("unserializable", e.getOriginalMessage());
            return marker;
        }
        if (serialized.length <= maxBytes) {
            return trace;
        }

        String full = new String(serialized, StandardCharsets.UTF_8);
        // Budget for the head: total cap minus the marker skeleton (computed
        // with an empty head, then refined — JSON string escaping can grow the
        // head, so shrink until the marker itself fits).
        int headChars = Math.max(0, maxBytes);
        ObjectNode marker;
        do {
            marker = mapper.createObjectNode();
            marker.put(TRUNCATED_FIELD, true);
            marker.put(ORIGINAL_BYTES_FIELD, serialized.length);
            marker.put(HEAD_FIELD, safeTruncate(full, headChars));
            int markerBytes = utf8Length(mapper, marker);
            if (markerBytes <= maxBytes) {
                return marker;
            }
            headChars -= Math.max(16, markerBytes - maxBytes);
        } while (headChars > 0);

        marker = mapper.createObjectNode();
        marker.put(TRUNCATED_FIELD, true);
        marker.put(ORIGINAL_BYTES_FIELD, serialized.length);
        return marker;                               // cap smaller than any head — marker only
    }

    private static String safeTruncate(String s, int chars) {
        if (s.length() <= chars) {
            return s;
        }
        int cut = chars;
        // never split a surrogate pair
        if (cut > 0 && Character.isHighSurrogate(s.charAt(cut - 1))) {
            cut--;
        }
        return s.substring(0, cut);
    }

    private static int utf8Length(ObjectMapper mapper, JsonNode node) {
        try {
            return mapper.writeValueAsBytes(node).length;
        } catch (JsonProcessingException e) {
            return Integer.MAX_VALUE;
        }
    }
}
