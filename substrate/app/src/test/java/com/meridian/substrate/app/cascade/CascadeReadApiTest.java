package com.meridian.substrate.app.cascade;

import com.meridian.substrate.common.domain.Cascade;
import com.meridian.substrate.common.domain.CascadeDraft;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §6.1 read-API shape tests (engine-spec cascade.md; spec §12 read-model-first
 * boundary): list/detail/actions/attention/notifications/stats served from
 * service-seeded data — every cascade is created by driving the real lifecycle
 * verbs, never row inserts. Wire vocabulary is the fixture vocabulary
 * (kebab-case states, ADR D13); {@code CascadeDetail.levels[]} is 1:1 with the
 * Beacon CascadeLevel view type (§11.1).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class CascadeReadApiTest extends CascadeApiTestSupport {

    private static final String ISO_DATE = "\\d{4}-\\d{2}-\\d{2}";

    @Autowired
    MockMvc mvc;

    // ---------------------------------------------------------------- list

    @Test
    void listReturnsSummariesInOperatorAttentionOrder() throws Exception {
        UUID domainId = createDomain("Holding Co");
        seedAbsorbed(domainId);
        seedEscalating(domainId, null, "Sentari Lab Operations Director", null);
        Cascade declinePending = seedDeclinePending(domainId, null);

        // §11.4 operator attention order: decline-pending first, then escalating,
        // absorbed last (no overdue rows in this seed).
        mvc.perform(get("/api/v1/cascades").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[0].id", is(declinePending.getId().toString())))
                .andExpect(jsonPath("$.data[0].attentionSignal", is("decline-pending")))
                .andExpect(jsonPath("$.data[0].currentState", is("in-flight")))
                .andExpect(jsonPath("$.data[0].frontierDepth", is(2)))
                .andExpect(jsonPath("$.data[0].frontierState", is("declined")))
                .andExpect(jsonPath("$.data[0].frontierOwnerLabel", is(L2_OWNER)))
                .andExpect(jsonPath("$.data[0].levelCount", is(3)))
                .andExpect(jsonPath("$.data[0].chainSeq", is(3)))
                .andExpect(jsonPath("$.data[0].originatedBy", is(ORIGINATOR)))
                .andExpect(jsonPath("$.data[0].overdue", is(false)))
                .andExpect(jsonPath("$.data[0].originatedAt", notNullValue()))
                .andExpect(jsonPath("$.data[0].lastActivityAt", notNullValue()))
                .andExpect(jsonPath("$.data[0].originalCritique", notNullValue()))
                .andExpect(jsonPath("$.data[0].acceptanceCriteria", notNullValue()))
                .andExpect(jsonPath("$.data[1].attentionSignal", is("escalating")))
                .andExpect(jsonPath("$.data[1].frontierState", is("looking")))
                .andExpect(jsonPath("$.data[2].attentionSignal", is("absorbed")))
                .andExpect(jsonPath("$.data[2].frontierState", is("absorbed")));
    }

    @Test
    void listFiltersBySignalStateAndGroupSpeakWireVocabulary() throws Exception {
        UUID domainId = createDomain("Holding Co");
        UUID groupId = UUID.randomUUID();
        seedDeclinePending(domainId, groupId);
        seedEscalating(domainId, null, "Tellix Engineering Director", null);
        Cascade resolved = seedAbsorbed(domainId);
        resolveLooksGood(resolved.getId());

        mvc.perform(get("/api/v1/cascades")
                        .param("domainId", domainId.toString())
                        .param("signal", "decline-pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].attentionSignal", is("decline-pending")));

        mvc.perform(get("/api/v1/cascades")
                        .param("domainId", domainId.toString())
                        .param("state", "in-flight"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)));

        mvc.perform(get("/api/v1/cascades")
                        .param("domainId", domainId.toString())
                        .param("state", "resolved-looks-good"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id", is(resolved.getId().toString())))
                .andExpect(jsonPath("$.data[0].currentState", is("resolved-looks-good")));

        mvc.perform(get("/api/v1/cascades")
                        .param("domainId", domainId.toString())
                        .param("groupId", groupId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].cascadeGroupId", is(groupId.toString())));

        // Unknown wire value → plain 400 (never anything resembling a cascade
        // failure state — the vocabulary is filter input only).
        mvc.perform(get("/api/v1/cascades")
                        .param("domainId", domainId.toString())
                        .param("signal", "bogus"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code", is("BAD_REQUEST")));
    }

    @Test
    void listResolvesAffectedDomainName() throws Exception {
        UUID domainId = createDomain("Holding Co");
        UUID affected = createDomain("Curasync Clinical Systems");
        CascadeDraft draft = openDraft(domainId, null, affected);
        issue(draft, null, L1_OWNER, null);

        mvc.perform(get("/api/v1/cascades").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].affectedDomainId", is(affected.toString())))
                .andExpect(jsonPath("$.data[0].affectedDomainName",
                        is(domainRepository.findById(affected).orElseThrow().getName())));
    }

    // -------------------------------------------------------------- detail

    @Test
    void detailLevelsMatchTheBeaconViewTypeIncludingLookingRows() throws Exception {
        UUID domainId = createDomain("Holding Co");
        Cascade escalating = seedEscalating(domainId, null, "Sentari Lab Operations Director", null);

        mvc.perform(get("/api/v1/cascades/{id}", escalating.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.levels", hasSize(3)))
                // L0: originator row, cascaded at issuance, refined one-liner.
                .andExpect(jsonPath("$.data.levels[0].level", is(0)))
                .andExpect(jsonPath("$.data.levels[0].ownerLabel", is(ORIGINATOR)))
                .andExpect(jsonPath("$.data.levels[0].state", is("cascaded")))
                .andExpect(jsonPath("$.data.levels[0].round", is(1)))
                .andExpect(jsonPath("$.data.levels[0].refinedCritique", notNullValue()))
                .andExpect(jsonPath("$.data.levels[0].declineReasoning", nullValue()))
                .andExpect(jsonPath("$.data.levels[0].timestamp", matchesPattern(ISO_DATE)))
                // L1 acted (cascaded), carries its refinement.
                .andExpect(jsonPath("$.data.levels[1].level", is(1)))
                .andExpect(jsonPath("$.data.levels[1].state", is("cascaded")))
                .andExpect(jsonPath("$.data.levels[1].refinedCritique", notNullValue()))
                // L2: frontier LOOKING — a row the fixtures never contained
                // (§11.2): passes through with activation date, no acted fields.
                .andExpect(jsonPath("$.data.levels[2].level", is(2)))
                .andExpect(jsonPath("$.data.levels[2].state", is("looking")))
                .andExpect(jsonPath("$.data.levels[2].ownerLabel", is("Sentari Lab Operations Director")))
                .andExpect(jsonPath("$.data.levels[2].actedAt", nullValue()))
                .andExpect(jsonPath("$.data.levels[2].activatedAt", notNullValue()))
                .andExpect(jsonPath("$.data.levels[2].timestamp", matchesPattern(ISO_DATE)))
                .andExpect(jsonPath("$.data.levels[2].overdue", is(false)))
                // In-flight: no resolution block yet.
                .andExpect(jsonPath("$.data.resolution", nullValue()))
                .andExpect(jsonPath("$.data.siblingDeclineAdvisories", hasSize(0)));
    }

    @Test
    void detailCarriesResolutionBlockAfterResolveDeclined() throws Exception {
        UUID domainId = createDomain("Holding Co");
        Cascade c = seedDeclinePending(domainId, null);
        UUID declineActionId = latestActionId(c.getId());
        resolveDeclined(c.getId(), declineActionId);

        // resolved-declined is a peer terminal outcome on the wire — success
        // envelope, no failure vocabulary anywhere (ADR-001 non-negotiable #1).
        mvc.perform(get("/api/v1/cascades/{id}", c.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.currentState", is("resolved-declined")))
                .andExpect(jsonPath("$.data.attentionSignal", is("resolved-declined")))
                .andExpect(jsonPath("$.data.resolution.resolvedByLabel", is(ORIGINATOR)))
                .andExpect(jsonPath("$.data.resolution.note", is("Decline accepted")))
                .andExpect(jsonPath("$.data.resolution.resolvedAt", notNullValue()))
                .andExpect(jsonPath("$.data.resolution.acceptedDeclineActionId",
                        is(declineActionId.toString())))
                // The declined level stays honestly visible in history.
                .andExpect(jsonPath("$.data.levels[2].state", is("declined")))
                .andExpect(jsonPath("$.data.levels[2].declineReasoning", notNullValue()));
    }

    @Test
    void detailSurfacesSiblingDeclineAdvisoriesReadOnly() throws Exception {
        UUID domainId = createDomain("Holding Co");
        UUID groupId = UUID.randomUUID();
        // Sibling first (must be in-flight when the decline lands), then the
        // declining cascade in the same group.
        Cascade sibling = seedEscalating(domainId, groupId, "Tellix Engineering Director", null);
        Cascade declining = seedDeclinePending(domainId, groupId);

        // ADR D2: advisory only — anchored to the SIBLING, zero state change.
        mvc.perform(get("/api/v1/cascades/{id}", sibling.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentState", is("in-flight")))
                .andExpect(jsonPath("$.data.attentionSignal", is("escalating")))
                .andExpect(jsonPath("$.data.siblingDeclineAdvisories", hasSize(1)))
                .andExpect(jsonPath("$.data.siblingDeclineAdvisories[0].decliningCascadeId",
                        is(declining.getId().toString())))
                .andExpect(jsonPath("$.data.siblingDeclineAdvisories[0].depth", is(2)))
                .andExpect(jsonPath("$.data.siblingDeclineAdvisories[0].declinedAt", notNullValue()))
                .andExpect(jsonPath("$.data.siblingDeclineAdvisories[0].reasoningExcerpt", notNullValue()));
    }

    @Test
    void detailUnknownIdIs404() throws Exception {
        mvc.perform(get("/api/v1/cascades/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code", is("NOT_FOUND")));
    }

    // ------------------------------------------------------------- actions

    @Test
    void actionsTimelineIsChainSeqAscWithConditionalPayloads() throws Exception {
        UUID domainId = createDomain("Holding Co");
        Cascade c = seedDeclinePending(domainId, null);

        mvc.perform(get("/api/v1/cascades/{id}/actions", c.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[0].chainSeq", is(1)))
                .andExpect(jsonPath("$.data[0].action", is("issue")))
                .andExpect(jsonPath("$.data[0].depth", is(0)))
                .andExpect(jsonPath("$.data[0].actorLabel", is(ORIGINATOR)))
                .andExpect(jsonPath("$.data[0].refinedCritique", notNullValue()))
                .andExpect(jsonPath("$.data[0].nextOwnerLabel", is(L1_OWNER)))
                .andExpect(jsonPath("$.data[1].chainSeq", is(2)))
                .andExpect(jsonPath("$.data[1].action", is("cascade")))
                .andExpect(jsonPath("$.data[1].nextOwnerLabel", is(L2_OWNER)))
                .andExpect(jsonPath("$.data[2].chainSeq", is(3)))
                .andExpect(jsonPath("$.data[2].action", is("decline")))
                .andExpect(jsonPath("$.data[2].declineReasoning", notNullValue()))
                .andExpect(jsonPath("$.data[2].refinedCritique", nullValue()))
                .andExpect(jsonPath("$.data[2].resolutionNote", nullValue()))
                .andExpect(jsonPath("$.data[2].actedAt", notNullValue()));
    }

    @Test
    void actionsPaginationWindowsTheTimeline() throws Exception {
        UUID domainId = createDomain("Holding Co");
        Cascade c = seedDeclinePending(domainId, null);

        mvc.perform(get("/api/v1/cascades/{id}/actions", c.getId())
                        .param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].chainSeq", is(1)))
                .andExpect(jsonPath("$.data[1].chainSeq", is(2)));

        mvc.perform(get("/api/v1/cascades/{id}/actions", c.getId())
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].chainSeq", is(3)));
    }

    @Test
    void actionsUnknownIdIs404() throws Exception {
        mvc.perform(get("/api/v1/cascades/{id}/actions", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code", is("NOT_FOUND")));
    }

    // ----------------------------------------------------------- attention

    @Test
    void attentionDerivesKindsAndTonesFromInFlightFrontiers() throws Exception {
        UUID domainId = createDomain("Holding Co");
        seedDeclinePending(domainId, null);
        seedEscalating(domainId, null, "Sentari Lab Operations Director", null);
        seedAbsorbed(domainId); // closed §6.1 kind set: absorbed frontier emits no item

        mvc.perform(get("/api/v1/cascades/attention").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data", hasSize(3)))
                // Destructive = leader-attention (queue #21), first in order.
                .andExpect(jsonPath("$.data[0].kind", is("decline-pending-originator")))
                .andExpect(jsonPath("$.data[0].tone", is("destructive")))
                .andExpect(jsonPath("$.data[0].depth", is(2)))
                .andExpect(jsonPath("$.data[0].ownerLabel", is(ORIGINATOR)))
                .andExpect(jsonPath("$.data[0].headline",
                        is("Decline pending — " + L2_OWNER + " declined at L2")))
                .andExpect(jsonPath("$.data[0].since", notNullValue()))
                .andExpect(jsonPath("$.data[1].kind", is("respond-to-decline")))
                .andExpect(jsonPath("$.data[1].tone", is("destructive")))
                .andExpect(jsonPath("$.data[1].depth", is(1)))
                .andExpect(jsonPath("$.data[1].ownerLabel", is(L1_OWNER)))
                .andExpect(jsonPath("$.data[2].kind", is("level-looking")))
                .andExpect(jsonPath("$.data[2].tone", is("info")))
                .andExpect(jsonPath("$.data[2].ownerLabel", is("Sentari Lab Operations Director")));
    }

    @Test
    void attentionOwnerIdFiltersToTheDemandedParty() throws Exception {
        UUID domainId = createDomain("Holding Co");
        UUID lookingOwner = UUID.randomUUID();
        seedDeclinePending(domainId, null);
        seedEscalating(domainId, null, "Sentari Lab Operations Director", lookingOwner);

        mvc.perform(get("/api/v1/cascades/attention")
                        .param("domainId", domainId.toString())
                        .param("ownerId", lookingOwner.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].kind", is("level-looking")))
                .andExpect(jsonPath("$.data[0].ownerLabel", is("Sentari Lab Operations Director")));
    }

    // ------------------------------------------------------- notifications

    @Test
    void notificationsFeedServesOutboxRowsForARecipient() throws Exception {
        UUID domainId = createDomain("Holding Co");
        UUID recipient = UUID.randomUUID();
        Cascade c = seedEscalating(domainId, null, "Sentari Lab Operations Director", recipient);

        mvc.perform(get("/api/v1/cascades/notifications")
                        .param("recipientId", recipient.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].type", is("level-activated")))
                .andExpect(jsonPath("$.data[0].cascadeId", is(c.getId().toString())))
                .andExpect(jsonPath("$.data[0].recipientMemberId", is(recipient.toString())))
                .andExpect(jsonPath("$.data[0].recipientLabel", is("Sentari Lab Operations Director")))
                .andExpect(jsonPath("$.data[0].payload.headline", notNullValue()))
                .andExpect(jsonPath("$.data[0].payload.cascadeId", is(c.getId().toString())))
                .andExpect(jsonPath("$.data[0].readAt", nullValue()));

        // unreadOnly=false returns the full feed (nothing read yet → same row).
        mvc.perform(get("/api/v1/cascades/notifications")
                        .param("recipientId", recipient.toString())
                        .param("unreadOnly", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)));
    }

    // --------------------------------------------------------------- stats

    @Test
    void statsAggregateStateSignalOverdueAndActivity() throws Exception {
        UUID domainId = createDomain("Holding Co");
        seedDeclinePending(domainId, null);   // 3 actions
        seedEscalating(domainId, null, "Tellix Engineering Director", null); // 2 actions
        seedAbsorbed(domainId);               // 2 actions

        mvc.perform(get("/api/v1/cascades/stats").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.domainId", is(domainId.toString())))
                .andExpect(jsonPath("$.data.total", is(3)))
                .andExpect(jsonPath("$.data.byState['in-flight']", is(3)))
                .andExpect(jsonPath("$.data.byState['resolved-looks-good']", is(0)))
                .andExpect(jsonPath("$.data.byState['resolved-declined']", is(0)))
                .andExpect(jsonPath("$.data.bySignal['decline-pending']", is(1)))
                .andExpect(jsonPath("$.data.bySignal['escalating']", is(1)))
                .andExpect(jsonPath("$.data.bySignal['absorbed']", is(1)))
                .andExpect(jsonPath("$.data.overdueCount", is(0)))
                .andExpect(jsonPath("$.data.actionsLast7Days", is(7)));
    }
}
