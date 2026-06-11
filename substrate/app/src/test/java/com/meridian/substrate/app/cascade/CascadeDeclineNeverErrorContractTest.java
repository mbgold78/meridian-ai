package com.meridian.substrate.app.cascade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.Cascade;
import com.meridian.substrate.common.domain.enums.CascadeActionType;
import com.meridian.substrate.common.domain.enums.CascadeAttentionSignal;
import com.meridian.substrate.common.domain.enums.CascadeAuthoringMode;
import com.meridian.substrate.common.domain.enums.CascadeDraftStatus;
import com.meridian.substrate.common.domain.enums.CascadeLevelState;
import com.meridian.substrate.common.domain.enums.CascadeNotificationType;
import com.meridian.substrate.common.domain.enums.CascadeState;
import com.meridian.substrate.common.domain.enums.CascadeSuggestionState;
import com.meridian.substrate.engine.cascade.read.AttentionItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PINNED contract test — decline is NEVER an error (engine-spec cascade.md §12
 * test #9; ADR-001 non-negotiable #1; [CASCADE-LIFECYCLE]).
 *
 * <p>Runs MockMvc through the REAL {@code ResponseEnvelopeAdvice} +
 * {@code GlobalExceptionHandler} chain (full {@code @SpringBootTest} web
 * context) against the PRODUCTION §6.2 command endpoints (re-pointed from the
 * pre-command-chunk probe, now retired). Actor identity rides the §5.5
 * persona headers ({@code X-Persona-Label}).
 *
 * <p>Pins: a legitimate DECLINE → HTTP 200 with a success envelope
 * ({@code errors=[]}, populated {@code data}); RESOLVE(declined) → HTTP 200
 * peer-terminal; idempotent decline replay → still 200; and no
 * governance/lifecycle cascade enum value serializes to anything containing
 * "error" or "fail".
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class CascadeDeclineNeverErrorContractTest extends CascadeApiTestSupport {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    @Test
    void declineReturns200WithSuccessEnvelopeAndEmptyErrors() throws Exception {
        UUID domainId = createDomain("Holding Co");
        Cascade c = issue(openDraft(domainId, null, null), null, L1_OWNER, null);
        cascadeLevel(c.getId(), 1, L1_OWNER, L2_OWNER, null);

        String declineKey = key();
        String body = json.writeValueAsString(Map.of(
                "idempotencyKey", declineKey,
                "declineReasoning", "HIPAA gate makes the premise unworkable at this level"));

        // A legitimate decline → 200 with the success envelope: errors is the
        // EMPTY array, data carries the moved-on detail (decline-pending is
        // leader-attention, not failure).
        mvc.perform(post("/api/v1/cascades/{id}/levels/{depth}/decline", c.getId(), 2)
                        .header("X-Persona-Label", L2_OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.currentState", is("in-flight")))
                .andExpect(jsonPath("$.data.attentionSignal", is("decline-pending")))
                .andExpect(jsonPath("$.data.frontierState", is("declined")))
                .andExpect(jsonPath("$.data.levels[2].state", is("declined")))
                .andExpect(jsonPath("$.data.levels[2].declineReasoning",
                        is("HIPAA gate makes the premise unworkable at this level")));

        // Idempotent replay of the same decline key → the original 200
        // semantics again, never a conflict (ADR D8).
        mvc.perform(post("/api/v1/cascades/{id}/levels/{depth}/decline", c.getId(), 2)
                        .header("X-Persona-Label", L2_OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.chainSeq", is(3)));
    }

    @Test
    void resolveDeclinedReturns200AsPeerTerminalOutcome() throws Exception {
        UUID domainId = createDomain("Holding Co");
        Cascade c = seedDeclinePending(domainId, null);
        UUID declineActionId = latestActionId(c.getId());

        String body = json.writeValueAsString(Map.of(
                "idempotencyKey", key(),
                "resolution", "declined",
                "resolutionNote", "Decline accepted — criteria allowed a documented Decline",
                "acceptedDeclineActionId", declineActionId.toString()));

        mvc.perform(post("/api/v1/cascades/{id}/resolve", c.getId())
                        .header("X-Persona-Label", ORIGINATOR)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.currentState", is("resolved-declined")))
                .andExpect(jsonPath("$.data.attentionSignal", is("resolved-declined")))
                .andExpect(jsonPath("$.data.resolution.acceptedDeclineActionId",
                        is(declineActionId.toString())))
                .andExpect(jsonPath("$.data.resolution.resolvedByLabel", is(ORIGINATOR)));
    }

    /**
     * No governance/lifecycle cascade enum value — name or kebab wire form —
     * contains "error" or "fail" (spec §12 test #9). {@code CascadeSuggestionState}
     * is deliberately OUT of scope: its spec-mandated {@code ERROR} value (§2)
     * is AI-assist plumbing status only — "the orchestrator dispatch failed,
     * never anything about declines" (§6.3) — and never governance state; the
     * companion assertion pins that confinement.
     */
    @Test
    void noGovernanceEnumValueSerializesToErrorOrFailureVocabulary() {
        assertNoFailureVocabulary(CascadeState.values());
        assertNoFailureVocabulary(CascadeLevelState.values());
        assertNoFailureVocabulary(CascadeActionType.values());
        assertNoFailureVocabulary(CascadeAttentionSignal.values());
        assertNoFailureVocabulary(CascadeNotificationType.values());
        assertNoFailureVocabulary(CascadeAuthoringMode.values());
        assertNoFailureVocabulary(CascadeDraftStatus.values());
        assertNoFailureVocabulary(AttentionItem.Kind.values());
        assertNoFailureVocabulary(AttentionItem.Tone.values());

        // Confinement: ERROR exists ONLY as AI-assist plumbing on the draft
        // suggestion lane (§2) — pin that it never leaks into the state
        // machine vocabulary above (which the assertions just proved) and
        // that it is exactly the spec-stated value set.
        assertThat(CascadeSuggestionState.values())
                .extracting(Enum::name)
                .containsExactly("NONE", "PENDING", "READY", "ERROR");
    }

    private static void assertNoFailureVocabulary(Enum<?>[] values) {
        for (Enum<?> value : values) {
            String name = value.name().toLowerCase(Locale.ROOT);
            String wire = jsonWireForm(value).toLowerCase(Locale.ROOT);
            assertThat(name).as("%s.%s name", value.getDeclaringClass().getSimpleName(), value)
                    .doesNotContain("error").doesNotContain("fail");
            assertThat(wire).as("%s.%s wire form", value.getDeclaringClass().getSimpleName(), value)
                    .doesNotContain("error").doesNotContain("fail");
        }
    }

    /** Serializes through Jackson so the {@code @JsonValue} wire form is what is asserted. */
    private static String jsonWireForm(Enum<?> value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
