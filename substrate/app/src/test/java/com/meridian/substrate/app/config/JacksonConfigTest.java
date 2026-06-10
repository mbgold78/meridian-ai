package com.meridian.substrate.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the load-bearing Jackson configuration (port-map §2.2): ISO-8601 dates
 * (never numeric timestamps) and the Hibernate6Module that makes entity-direct
 * serialization safe.
 */
class JacksonConfigTest {

    record Stamp(Instant at) {}

    @Test
    void instants_serializeAsIso8601_notNumericTimestamps() throws Exception {
        ObjectMapper mapper = new JacksonConfig().objectMapper();

        String json = mapper.writeValueAsString(new Stamp(Instant.parse("2026-06-09T12:00:00Z")));

        assertThat(json).contains("\"2026-06-09T12:00:00Z\"");
    }

    @Test
    void hibernate6Module_isRegistered() {
        ObjectMapper mapper = new JacksonConfig().objectMapper();

        assertThat(mapper.getRegisteredModuleIds().toString())
                .as("Hibernate6Module must be registered — entity-direct serialization "
                        + "depends on lazy-proxy handling")
                .contains("hibernate6");
    }

    @Test
    void roundTrip_recordWithInstant() throws Exception {
        ObjectMapper mapper = new JacksonConfig().objectMapper();
        Stamp original = new Stamp(Instant.parse("2026-06-09T12:00:00Z"));

        Stamp back = mapper.readValue(mapper.writeValueAsString(original), Stamp.class);

        assertThat(back).isEqualTo(original);
    }
}
