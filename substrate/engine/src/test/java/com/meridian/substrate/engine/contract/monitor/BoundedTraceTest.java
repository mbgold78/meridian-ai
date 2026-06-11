package com.meridian.substrate.engine.contract.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §1 {@code substrate.contracts.trace.max-bytes} bounding (ADR D10
 * evaluation-trace graft): oversized traces are replaced by a
 * {@code "truncated": true} marker that itself fits the cap.
 */
class BoundedTraceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void smallTracePassesThroughUnchanged() throws Exception {
        ObjectNode trace = mapper.createObjectNode();
        trace.put("rule", "ATTESTATION_CADENCE");
        trace.put("periodKey", "2026-04");

        JsonNode bounded = BoundedTrace.bound(mapper, trace, 16384);

        assertThat(bounded).isSameAs(trace);
        assertThat(mapper.writeValueAsBytes(bounded).length).isLessThanOrEqualTo(16384);
    }

    @Test
    void oversizedTraceIsTruncatedWithMarkerWithinTheCap() throws Exception {
        ObjectNode trace = mapper.createObjectNode();
        trace.put("rule", "METRIC_THRESHOLD");
        trace.put("blob", "x".repeat(50_000));
        int cap = 1024;

        JsonNode bounded = BoundedTrace.bound(mapper, trace, cap);

        assertThat(bounded.get("truncated").asBoolean()).isTrue();
        assertThat(bounded.get("originalBytes").asInt())
                .isEqualTo(mapper.writeValueAsBytes(trace).length);
        assertThat(bounded.get("head").asText()).startsWith("{\"rule\":\"METRIC_THRESHOLD\"");
        assertThat(mapper.writeValueAsBytes(bounded).length)
                .as("the marker itself must fit the cap")
                .isLessThanOrEqualTo(cap);
    }

    @Test
    void tinyCapStillProducesAValidMarker() throws Exception {
        ObjectNode trace = mapper.createObjectNode();
        trace.put("blob", "y".repeat(10_000));
        int cap = 64;

        JsonNode bounded = BoundedTrace.bound(mapper, trace, cap);

        assertThat(bounded.get("truncated").asBoolean()).isTrue();
        assertThat(mapper.writeValueAsBytes(bounded).length).isLessThanOrEqualTo(cap);
    }

    @Test
    void multiByteContentIsCappedOnByteLengthNotCharCount() throws Exception {
        ObjectNode trace = mapper.createObjectNode();
        trace.put("blob", "Ω≠∑".repeat(20_000));      // multi-byte UTF-8
        int cap = 2048;

        JsonNode bounded = BoundedTrace.bound(mapper, trace, cap);

        assertThat(bounded.get("truncated").asBoolean()).isTrue();
        byte[] bytes = mapper.writeValueAsBytes(bounded);
        assertThat(bytes.length).isLessThanOrEqualTo(cap);
        // round-trips as valid JSON/UTF-8 (no split surrogate)
        assertThat(mapper.readTree(new String(bytes, StandardCharsets.UTF_8))
                .get("truncated").asBoolean()).isTrue();
    }

    @Test
    void nullTracePassesThrough() {
        assertThat(BoundedTrace.bound(mapper, null, 1024)).isNull();
    }
}
