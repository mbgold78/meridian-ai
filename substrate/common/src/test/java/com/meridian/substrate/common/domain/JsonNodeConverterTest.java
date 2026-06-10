package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link JsonNodeConverter} — the JSON-over-TEXT persistence
 * mechanism shared by every JSON column in the canonical model (port-map §4.5).
 */
class JsonNodeConverterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonNodeConverter converter = new JsonNodeConverter();

    @Test
    void roundTripsObjectNode() throws Exception {
        JsonNode original = MAPPER.readTree("{\"key\":\"value\",\"nested\":{\"n\":1},\"arr\":[1,2,3]}");

        String column = converter.convertToDatabaseColumn(original);
        JsonNode restored = converter.convertToEntityAttribute(column);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void roundTripsArrayNode() throws Exception {
        JsonNode original = MAPPER.readTree("[\"a\",\"b\",{\"c\":true}]");

        String column = converter.convertToDatabaseColumn(original);
        JsonNode restored = converter.convertToEntityAttribute(column);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void nullAttributeStoresNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void nullColumnReadsAsNull() {
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void blankColumnReadsAsNull() {
        assertThat(converter.convertToEntityAttribute("")).isNull();
        assertThat(converter.convertToEntityAttribute("   ")).isNull();
    }

    @Test
    void malformedColumnThrowsIllegalArgument() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("{not json"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
