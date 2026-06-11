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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §6.2 command-API Postgres gate (engine-spec cascade.md §12 test #4 HTTP half
 * over real Postgres 16): idempotency replay returns the original 200
 * semantics with no new action row (the {@code uq_cla_idem} UNIQUE backstop
 * and the PESSIMISTIC_WRITE {@code SELECT … FOR UPDATE} are real here), stale
 * {@code expectedChainSeq} → 409 {@code CASCADE_STALE_STATE} with the current
 * detail in the envelope {@code data}, and the 403 actor guard — through the
 * production endpoints + persona headers (§5.5).
 *
 * <p><b>Docker-less machines (e.g. the Windows dev box):</b> self-disables via
 * {@code @Testcontainers(disabledWithoutDocker = true)}; CI (ubuntu-latest)
 * runs the real gate. H2-green is never acceptance (port-map §4.4).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class PostgresCascadeCommandApiIT extends CascadeApiTestSupport {

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
    void idempotencyReplayAndStaleFencingHoldOnPostgres() throws Exception {
        UUID domainId = createDomain("Holding Co");
        Cascade c = issue(openDraft(domainId, null, null), null, L1_OWNER, null); // chain_seq 1

        // Stale fencing token → 409 with the CURRENT detail in data (§6.2).
        mvc.perform(post("/api/v1/cascades/{id}/levels/{depth}/cascade", c.getId(), 1)
                        .header("X-Persona-Label", L1_OWNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idempotencyKey", key(),
                                "expectedChainSeq", 42,
                                "refinedCritique", "Stale view of the chain",
                                "nextOwnerLabel", L2_OWNER))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code", is("CASCADE_STALE_STATE")))
                .andExpect(jsonPath("$.data.id", is(c.getId().toString())))
                .andExpect(jsonPath("$.data.chainSeq", is(1)));

        // Command + idempotent replay → one CASCADE row total.
        String cascadeKey = key();
        String body = json.writeValueAsString(Map.of(
                "idempotencyKey", cascadeKey,
                "expectedChainSeq", 1,
                "refinedCritique", "Refined for the HIM director",
                "nextOwnerLabel", L2_OWNER));
        mvc.perform(post("/api/v1/cascades/{id}/levels/{depth}/cascade", c.getId(), 1)
                        .header("X-Persona-Label", L1_OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.chainSeq", is(2)));
        mvc.perform(post("/api/v1/cascades/{id}/levels/{depth}/cascade", c.getId(), 1)
                        .header("X-Persona-Label", L1_OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.chainSeq", is(2)));
        assertThat(actionRepository.findByCascadeIdOrderByChainSeqAsc(c.getId()))
                .as("idempotent replay must not append an action row on Postgres")
                .hasSize(2);

        // Actor guard: 403 with the spec code, never a generic failure.
        mvc.perform(post("/api/v1/cascades/{id}/levels/{depth}/decline", c.getId(), 2)
                        .header("X-Persona-Label", "Somebody Else")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idempotencyKey", key(),
                                "declineReasoning", "Not the owner"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code", is("CASCADE_NOT_LEVEL_OWNER")));
    }
}
