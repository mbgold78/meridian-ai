package com.meridian.substrate.app.cascade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.Cascade;
import com.meridian.substrate.common.domain.CascadeNotification;
import com.meridian.substrate.common.repo.CascadeNotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §6.2 command REST API over the production endpoints (engine-spec cascade.md
 * §6.2; §12 test #4 HTTP half): idempotency replay → the original 200
 * semantics with NO new action row; stale {@code expectedChainSeq} → 409
 * {@code CASCADE_STALE_STATE} with the CURRENT {@code CascadeDetail} in the
 * envelope {@code data}; plus the full verb surface (cascade / absorb /
 * decline / endorse-decline / recascade / resolve), the 403 actor guards, the
 * 404 unknown-id row, terminal absorption (409 {@code CASCADE_TERMINAL}),
 * per-field bean-validation 400s, and notification mark-read.
 *
 * <p>Actor identity rides the §5.5 persona headers: {@code X-Persona-Label} →
 * actor label, {@code X-Persona-Id} → actor member id when parseable as a
 * UUID. H2 dev-profile lane; Testcontainers-Postgres in CI is the acceptance
 * gate (PostgresCascadeCommandApiIT), H2-green never is.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class CascadeCommandApiTest extends CascadeApiTestSupport {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    CascadeNotificationRepository notificationRepository;

    // ----------------------------------------------------- §12 #4 idempotency

    @Test
    void idempotencyReplayReturns200WithoutNewActionRow() throws Exception {
        UUID domainId = createDomain("Holding Co");
        Cascade c = issue(openDraft(domainId, null, null), null, L1_OWNER, null);

        String cascadeKey = key();
        String body = json.writeValueAsString(Map.of(
                "idempotencyKey", cascadeKey,
                "refinedCritique", "Refined for the HIM director",
                "nextOwnerLabel", L2_OWNER));

        mvc.perform(post("/api/v1/cascades/{id}/levels/{depth}/cascade", c.getId(), 1)
                        .header("X-Persona-Label", L1_OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.chainSeq", is(2)))
                .andExpect(jsonPath("$.data.frontierDepth", is(2)))
                .andExpect(jsonPath("$.data.frontierState", is("looking")));
        long actionsAfterFirst = actionRepository
                .findByCascadeIdOrderByChainSeqAsc(c.getId()).size();
        assertThat(actionsAfterFirst).isEqualTo(2); // ISSUE + CASCADE

        // Replay of the SAME key → 200 success envelope again, no new action
        // row, chain_seq unmoved (ADR D8: the original result re-projected).
        mvc.perform(post("/api/v1/cascades/{id}/levels/{depth}/cascade", c.getId(), 1)
                        .header("X-Persona-Label", L1_OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.chainSeq", is(2)));
        assertThat(actionRepository.findByCascadeIdOrderByChainSeqAsc(c.getId()))
                .as("idempotent replay must not append an action row")
                .hasSize(2);
    }

    // --------------------------------------------------------- §12 #4 fencing

    @Test
    void staleExpectedChainSeqYields409WithCurrentDetailInData() throws Exception {
        UUID domainId = createDomain("Holding Co");
        Cascade c = issue(openDraft(domainId, null, null), null, L1_OWNER, null); // chain_seq = 1

        String body = json.writeValueAsString(Map.of(
                "idempotencyKey", key(),
                "expectedChainSeq", 42,
                "refinedCritique", "Built on a stale read of the chain",
                "nextOwnerLabel", L2_OWNER));

        // 409 CASCADE_STALE_STATE; data carries the CURRENT CascadeDetail so
        // the UI renders "cascade moved on — review current state" (§11.5).
        mvc.perform(post("/api/v1/cascades/{id}/levels/{depth}/cascade", c.getId(), 1)
                        .header("X-Persona-Label", L1_OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code", is("CASCADE_STALE_STATE")))
                .andExpect(jsonPath("$.data.id", is(c.getId().toString())))
                .andExpect(jsonPath("$.data.chainSeq", is(1)))
                .andExpect(jsonPath("$.data.currentState", is("in-flight")))
                .andExpect(jsonPath("$.data.levels", hasSize(2)));
        assertThat(actionRepository.findByCascadeIdOrderByChainSeqAsc(c.getId()))
                .as("a fenced-out command must not append an action row")
                .hasSize(1);

        // A matching fencing token passes.
        String fresh = json.writeValueAsString(Map.of(
                "idempotencyKey", key(),
                "expectedChainSeq", 1,
                "refinedCritique", "Built on the current chain",
                "nextOwnerLabel", L2_OWNER));
        mvc.perform(post("/api/v1/cascades/{id}/levels/{depth}/cascade", c.getId(), 1)
                        .header("X-Persona-Label", L1_OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content(fresh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chainSeq", is(2)));
    }

    // ------------------------------------------------------- full verb surface

    /** L5 recascade → round-2 LOOKING, then L6-shape absorb at the level above, then A2 resolve. */
    @Test
    void recascadeAbsorbAndResolveLooksGoodThroughHttp() throws Exception {
        UUID domainId = createDomain("Holding Co");
        Cascade c = seedDeclinePending(domainId, null); // frontier L2 DECLINED

        mvc.perform(post("/api/v1/cascades/{id}/levels/{depth}/recascade", c.getId(), 1)
                        .header("X-Persona-Label", L1_OWNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idempotencyKey", key(),
                                "refinedCritique", "Narrowed to the HIPAA-safe slice",
                                "nextOwnerLabel", "Curasync Compliance Lead"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.frontierDepth", is(2)))
                .andExpect(jsonPath("$.data.frontierState", is("looking")))
                .andExpect(jsonPath("$.data.levels[2].round", is(2)))
                .andExpect(jsonPath("$.data.levels[2].ownerLabel", is("Curasync Compliance Lead")));

        mvc.perform(post("/api/v1/cascades/{id}/levels/{depth}/absorb", c.getId(), 2)
                        .header("X-Persona-Label", "Curasync Compliance Lead")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idempotencyKey", key(),
                                "refinedCritique", "Taking the compliance review in-house"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attentionSignal", is("absorbed")))
                .andExpect(jsonPath("$.data.levels[2].state", is("absorbed")));

        mvc.perform(post("/api/v1/cascades/{id}/resolve", c.getId())
                        .header("X-Persona-Label", ORIGINATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idempotencyKey", key(),
                                "resolution", "looks-good",
                                "resolutionNote", "Absorbed outcome accepted"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.currentState", is("resolved-looks-good")))
                .andExpect(jsonPath("$.data.resolution.note", is("Absorbed outcome accepted")))
                .andExpect(jsonPath("$.data.resolution.acceptedDeclineActionId", nullValue()));
    }

    /** L4 endorse-decline grows the run upward; A3 accepts the endorsing decline. */
    @Test
    void endorseDeclineThenResolveDeclinedThroughHttp() throws Exception {
        UUID domainId = createDomain("Holding Co");
        Cascade c = seedDeclinePending(domainId, null); // frontier L2 DECLINED

        mvc.perform(post("/api/v1/cascades/{id}/levels/{depth}/decline", c.getId(), 1)
                        .header("X-Persona-Label", L1_OWNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idempotencyKey", key(),
                                "declineReasoning", "I endorse the decline — the premise fails here too"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.frontierDepth", is(1)))
                .andExpect(jsonPath("$.data.frontierState", is("declined")))
                .andExpect(jsonPath("$.data.attentionSignal", is("decline-pending")));
        UUID endorseActionId = latestActionId(c.getId());

        mvc.perform(post("/api/v1/cascades/{id}/resolve", c.getId())
                        .header("X-Persona-Label", ORIGINATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idempotencyKey", key(),
                                "resolution", "declined",
                                "resolutionNote", "Endorsed decline accepted",
                                "acceptedDeclineActionId", endorseActionId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentState", is("resolved-declined")))
                .andExpect(jsonPath("$.data.resolution.acceptedDeclineActionId",
                        is(endorseActionId.toString())));
    }

    /** §5.5 actor mapping: X-Persona-Id (UUID) matches by member id; label is display-only then. */
    @Test
    void personaIdHeaderBindsActorMemberId() throws Exception {
        UUID domainId = createDomain("Holding Co");
        UUID originatorMemberId = UUID.randomUUID();
        Cascade c = issue(openDraft(domainId, null, null), originatorMemberId, L1_OWNER, null);

        mvc.perform(post("/api/v1/cascades/{id}/resolve", c.getId())
                        .header("X-Persona-Id", originatorMemberId.toString())
                        .header("X-Persona-Label", "OP (mobile)")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idempotencyKey", key(),
                                "resolution", "looks-good",
                                "resolutionNote", "Closed from the road"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentState", is("resolved-looks-good")))
                .andExpect(jsonPath("$.data.resolution.resolvedByLabel", is("OP (mobile)")));
    }

    // ------------------------------------------------------------- 403 guards

    @Test
    void nonOwnerActorIs403NotLevelOwner() throws Exception {
        UUID domainId = createDomain("Holding Co");
        Cascade c = issue(openDraft(domainId, null, null), null, L1_OWNER, null);

        mvc.perform(post("/api/v1/cascades/{id}/levels/{depth}/cascade", c.getId(), 1)
                        .header("X-Persona-Label", "Somebody Else")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idempotencyKey", key(),
                                "refinedCritique", "Not my level to act on",
                                "nextOwnerLabel", L2_OWNER))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors[0].code", is("CASCADE_NOT_LEVEL_OWNER")));
    }

    @Test
    void nonOriginatorResolveIs403NotOriginator() throws Exception {
        UUID domainId = createDomain("Holding Co");
        Cascade c = seedDeclinePending(domainId, null);

        mvc.perform(post("/api/v1/cascades/{id}/resolve", c.getId())
                        .header("X-Persona-Label", L1_OWNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idempotencyKey", key(),
                                "resolution", "looks-good",
                                "resolutionNote", "Trying to close someone else's cascade"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code", is("CASCADE_NOT_ORIGINATOR")));
    }

    // --------------------------------------------------- 409 terminal / 404 id

    @Test
    void postTerminalActionIs409CascadeTerminal() throws Exception {
        UUID domainId = createDomain("Holding Co");
        Cascade c = seedAbsorbed(domainId);
        resolveLooksGood(c.getId());

        mvc.perform(post("/api/v1/cascades/{id}/levels/{depth}/decline", c.getId(), 1)
                        .header("X-Persona-Label", L1_OWNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idempotencyKey", key(),
                                "declineReasoning", "Too late — chain already closed"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code", is("CASCADE_TERMINAL")))
                .andExpect(jsonPath("$.data.currentState", is("resolved-looks-good")));
    }

    @Test
    void unknownCascadeIdIs404() throws Exception {
        mvc.perform(post("/api/v1/cascades/{id}/levels/{depth}/decline", UUID.randomUUID(), 1)
                        .header("X-Persona-Label", L1_OWNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idempotencyKey", key(),
                                "declineReasoning", "No such cascade"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code", is("NOT_FOUND")));
    }

    // -------------------------------------------------- bean-validation 400s

    @Test
    void missingIdempotencyKeyIs400PerFieldValidationError() throws Exception {
        UUID domainId = createDomain("Holding Co");
        Cascade c = issue(openDraft(domainId, null, null), null, L1_OWNER, null);

        mvc.perform(post("/api/v1/cascades/{id}/levels/{depth}/cascade", c.getId(), 1)
                        .header("X-Persona-Label", L1_OWNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "refinedCritique", "No key supplied",
                                "nextOwnerLabel", L2_OWNER))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code", is("VALIDATION_ERROR")))
                .andExpect(jsonPath("$.errors[0].field", is("idempotencyKey")));
    }

    @Test
    void unknownResolutionValueIs400PerFieldValidationError() throws Exception {
        UUID domainId = createDomain("Holding Co");
        Cascade c = seedDeclinePending(domainId, null);

        Map<String, Object> body = new HashMap<>();
        body.put("idempotencyKey", key());
        body.put("resolution", "withdrawn"); // outside the binding terminal pair
        body.put("resolutionNote", "n/a");
        mvc.perform(post("/api/v1/cascades/{id}/resolve", c.getId())
                        .header("X-Persona-Label", ORIGINATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code", is("VALIDATION_ERROR")))
                .andExpect(jsonPath("$.errors[0].field", is("resolution")));
    }

    /** A3 guard half: resolution=declined without acceptedDeclineActionId → 409, not failure tone. */
    @Test
    void resolveDeclinedWithoutAcceptedDeclineActionIdIs409IllegalTransition() throws Exception {
        UUID domainId = createDomain("Holding Co");
        Cascade c = seedDeclinePending(domainId, null);

        mvc.perform(post("/api/v1/cascades/{id}/resolve", c.getId())
                        .header("X-Persona-Label", ORIGINATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idempotencyKey", key(),
                                "resolution", "declined",
                                "resolutionNote", "Accepting, but without citing the decline"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code", is("CASCADE_ILLEGAL_TRANSITION")))
                .andExpect(jsonPath("$.data.currentState", is("in-flight")));
    }

    // --------------------------------------------------- notifications /read

    @Test
    void notificationMarkReadIs200IdempotentAnd404ForUnknownId() throws Exception {
        UUID domainId = createDomain("Holding Co");
        UUID l2OwnerMemberId = UUID.randomUUID();
        Cascade c = seedEscalating(domainId, null, L2_OWNER, l2OwnerMemberId);
        CascadeNotification row = notificationRepository
                .findByRecipientMemberIdAndReadAtIsNullOrderByCreatedAtDesc(l2OwnerMemberId)
                .get(0);

        mvc.perform(post("/api/v1/cascades/notifications/{id}/read", row.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.id", is(row.getId().toString())))
                .andExpect(jsonPath("$.data.cascadeId", is(c.getId().toString())))
                .andExpect(jsonPath("$.data.readAt", notNullValue()));

        // Idempotent: the read watermark sticks, replay is still 200.
        mvc.perform(post("/api/v1/cascades/notifications/{id}/read", row.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)));

        mvc.perform(post("/api/v1/cascades/notifications/{id}/read", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code", is("NOT_FOUND")));
    }
}
