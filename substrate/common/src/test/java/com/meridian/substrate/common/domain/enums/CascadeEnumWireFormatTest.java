package com.meridian.substrate.common.domain.enums;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the engine-spec cascade.md §2 wire vocabulary: exact enum values,
 * kebab-case {@code @JsonValue} serialization (the fixture vocabulary, ADR
 * D13), round-trip deserialization, and the decline-never-error rule — no
 * governance enum value serializes to anything containing "error" or "fail"
 * (ADR-001 non-negotiable #1). {@link CascadeSuggestionState#ERROR} is the
 * deliberate, spec-defined exception: AI-assist plumbing status only, never
 * governance state (spec §2 + §6.3 DTO note).
 */
class CascadeEnumWireFormatTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void cascadeStateWireForms() throws Exception {
        assertThat(CascadeState.values()).containsExactly(
                CascadeState.IN_FLIGHT, CascadeState.RESOLVED_LOOKS_GOOD, CascadeState.RESOLVED_DECLINED);
        assertThat(MAPPER.writeValueAsString(CascadeState.IN_FLIGHT)).isEqualTo("\"in-flight\"");
        assertThat(MAPPER.writeValueAsString(CascadeState.RESOLVED_LOOKS_GOOD)).isEqualTo("\"resolved-looks-good\"");
        assertThat(MAPPER.writeValueAsString(CascadeState.RESOLVED_DECLINED)).isEqualTo("\"resolved-declined\"");
        assertThat(MAPPER.readValue("\"resolved-declined\"", CascadeState.class))
                .isEqualTo(CascadeState.RESOLVED_DECLINED);
    }

    @Test
    void cascadeLevelStateWireForms() throws Exception {
        assertThat(CascadeLevelState.values()).containsExactly(
                CascadeLevelState.LOOKING, CascadeLevelState.ABSORBED,
                CascadeLevelState.CASCADED, CascadeLevelState.DECLINED);
        assertThat(MAPPER.writeValueAsString(CascadeLevelState.LOOKING)).isEqualTo("\"looking\"");
        assertThat(MAPPER.writeValueAsString(CascadeLevelState.ABSORBED)).isEqualTo("\"absorbed\"");
        assertThat(MAPPER.writeValueAsString(CascadeLevelState.CASCADED)).isEqualTo("\"cascaded\"");
        assertThat(MAPPER.writeValueAsString(CascadeLevelState.DECLINED)).isEqualTo("\"declined\"");
        assertThat(MAPPER.readValue("\"declined\"", CascadeLevelState.class))
                .isEqualTo(CascadeLevelState.DECLINED);
    }

    @Test
    void cascadeActionTypeWireForms() throws Exception {
        assertThat(CascadeActionType.values()).containsExactly(
                CascadeActionType.ISSUE, CascadeActionType.CASCADE, CascadeActionType.ABSORB,
                CascadeActionType.DECLINE, CascadeActionType.RECASCADE,
                CascadeActionType.RESOLVE_LOOKS_GOOD, CascadeActionType.RESOLVE_DECLINED);
        assertThat(MAPPER.writeValueAsString(CascadeActionType.DECLINE)).isEqualTo("\"decline\"");
        assertThat(MAPPER.writeValueAsString(CascadeActionType.RESOLVE_LOOKS_GOOD))
                .isEqualTo("\"resolve-looks-good\"");
        assertThat(MAPPER.readValue("\"recascade\"", CascadeActionType.class))
                .isEqualTo(CascadeActionType.RECASCADE);
    }

    @Test
    void attentionSignalWireForms() throws Exception {
        assertThat(CascadeAttentionSignal.values()).containsExactly(
                CascadeAttentionSignal.DECLINE_PENDING, CascadeAttentionSignal.ESCALATING,
                CascadeAttentionSignal.ABSORBED, CascadeAttentionSignal.RESOLVED_LOOKS_GOOD,
                CascadeAttentionSignal.RESOLVED_DECLINED);
        assertThat(MAPPER.writeValueAsString(CascadeAttentionSignal.DECLINE_PENDING))
                .isEqualTo("\"decline-pending\"");
        assertThat(MAPPER.writeValueAsString(CascadeAttentionSignal.ESCALATING))
                .isEqualTo("\"escalating\"");
    }

    @Test
    void authoringDraftAndNotificationWireForms() throws Exception {
        assertThat(MAPPER.writeValueAsString(CascadeAuthoringMode.FREE_FORM)).isEqualTo("\"free-form\"");
        assertThat(MAPPER.writeValueAsString(CascadeAuthoringMode.AI_SUGGESTED)).isEqualTo("\"ai-suggested\"");
        assertThat(MAPPER.writeValueAsString(CascadeDraftStatus.OPEN)).isEqualTo("\"open\"");
        assertThat(MAPPER.writeValueAsString(CascadeSuggestionState.PENDING)).isEqualTo("\"pending\"");
        assertThat(MAPPER.writeValueAsString(CascadeNotificationType.DECLINE_PENDING_ORIGINATOR))
                .isEqualTo("\"decline-pending-originator\"");
        assertThat(MAPPER.writeValueAsString(CascadeNotificationType.SIBLING_DECLINE))
                .isEqualTo("\"sibling-decline\"");
    }

    /**
     * Decline is never an error: no governance enum value contains
     * "error"/"fail" in name or wire form (spec §12 test #9, enum half).
     * CascadeSuggestionState is deliberately excluded — its ERROR value is
     * AI-assist plumbing (orchestrator dispatch failed), defined by spec §2.
     */
    @Test
    void noGovernanceEnumValueContainsErrorOrFail() {
        List<Enum<?>[]> governanceEnums = List.of(
                CascadeState.values(), CascadeLevelState.values(), CascadeActionType.values(),
                CascadeAttentionSignal.values(), CascadeAuthoringMode.values(),
                CascadeDraftStatus.values(), CascadeNotificationType.values());

        for (Enum<?>[] values : governanceEnums) {
            for (Enum<?> value : values) {
                String name = value.name().toLowerCase(Locale.ROOT);
                assertThat(name)
                        .as("%s.%s must not carry failure vocabulary", value.getClass().getSimpleName(), value)
                        .doesNotContain("error")
                        .doesNotContain("fail");
            }
        }
    }
}
