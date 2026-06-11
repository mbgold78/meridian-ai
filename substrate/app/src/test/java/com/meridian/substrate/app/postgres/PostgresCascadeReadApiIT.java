package com.meridian.substrate.app.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.app.cascade.CascadeApiTestSupport;
import com.meridian.substrate.common.domain.Cascade;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cascade read-model Postgres gate (engine-spec cascade.md §6.1 over real
 * Postgres 16): the §12 read-API surface — list/detail/actions/attention/
 * notifications/stats plus the pinned decline-never-error envelope — exercised
 * end-to-end against V4+V4_1 under {@code ddl-auto=validate}, with all data
 * seeded by driving the real {@code CascadeLifecycleService} verb path (the
 * ADR D16 discipline; the PESSIMISTIC_WRITE lock here is a real
 * {@code SELECT … FOR UPDATE}).
 *
 * <p><b>Docker-less machines (e.g. the Windows dev box):</b> self-disables via
 * {@code @Testcontainers(disabledWithoutDocker = true)}; CI (ubuntu-latest)
 * runs the real gate. H2-green is never acceptance (port-map §4.4).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class PostgresCascadeReadApiIT extends CascadeApiTestSupport {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    @Test
    @Transactional
    void readSurfaceServesLifecycleSeededCascadesOnPostgres() throws Exception {
        UUID domainId = createDomain("Holding Co");
        UUID groupId = UUID.randomUUID();
        UUID lookingOwner = UUID.randomUUID();
        Cascade sibling = seedEscalating(domainId, groupId, "Tellix Engineering Director", lookingOwner);
        Cascade declinePending = seedDeclinePending(domainId, groupId);
        seedAbsorbed(domainId);

        // List — §11.4 operator attention order, kebab wire vocabulary.
        mvc.perform(get("/api/v1/cascades").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[0].id", is(declinePending.getId().toString())))
                .andExpect(jsonPath("$.data[0].attentionSignal", is("decline-pending")))
                .andExpect(jsonPath("$.data[0].frontierState", is("declined")))
                .andExpect(jsonPath("$.data[0].lastActivityAt", notNullValue()));

        // Detail — Beacon view-type levels incl. the LOOKING frontier row and
        // the ADR D2 sibling advisory; in-flight resolution is null.
        mvc.perform(get("/api/v1/cascades/{id}", sibling.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.levels", hasSize(3)))
                .andExpect(jsonPath("$.data.levels[2].state", is("looking")))
                .andExpect(jsonPath("$.data.levels[2].actedAt", nullValue()))
                .andExpect(jsonPath("$.data.resolution", nullValue()))
                .andExpect(jsonPath("$.data.siblingDeclineAdvisories", hasSize(1)))
                .andExpect(jsonPath("$.data.siblingDeclineAdvisories[0].decliningCascadeId",
                        is(declinePending.getId().toString())));

        // Actions — the single-throat-to-choke timeline, chain_seq asc.
        mvc.perform(get("/api/v1/cascades/{id}/actions", declinePending.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[0].action", is("issue")))
                .andExpect(jsonPath("$.data[2].action", is("decline")))
                .andExpect(jsonPath("$.data[2].declineReasoning", notNullValue()));

        // Attention — destructive leader-attention first.
        mvc.perform(get("/api/v1/cascades/attention").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[0].kind", is("decline-pending-originator")))
                .andExpect(jsonPath("$.data[0].tone", is("destructive")));

        // Notifications — both finder lanes (unread default + full feed).
        mvc.perform(get("/api/v1/cascades/notifications")
                        .param("recipientId", lookingOwner.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].type", is("level-activated")));
        mvc.perform(get("/api/v1/cascades/notifications")
                        .param("recipientId", lookingOwner.toString())
                        .param("unreadOnly", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)));

        // Stats — S6 engine-computed dashboard statistics.
        mvc.perform(get("/api/v1/cascades/stats").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total", is(3)))
                .andExpect(jsonPath("$.data.byState['in-flight']", is(3)))
                .andExpect(jsonPath("$.data.bySignal['decline-pending']", is(1)))
                .andExpect(jsonPath("$.data.actionsLast7Days", is(7)));
    }

    /** The §12 test #9 pinned envelope contract holds on real Postgres too. */
    @Test
    @Transactional
    void declineIs200WithSuccessEnvelopeOnPostgres() throws Exception {
        UUID domainId = createDomain("Holding Co");
        Cascade c = issue(openDraft(domainId, null, null), null, L1_OWNER, null);
        cascadeLevel(c.getId(), 1, L1_OWNER, L2_OWNER, null);

        String body = json.writeValueAsString(Map.of(
                "idempotencyKey", key(),
                "declineReasoning", "Documented pushback — premise unworkable at this level"));

        mvc.perform(post("/api/v1/cascades/{id}/levels/{depth}/decline", c.getId(), 2)
                        .header("X-Persona-Label", L2_OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.currentState", is("in-flight")))
                .andExpect(jsonPath("$.data.attentionSignal", is("decline-pending")));
    }
}
