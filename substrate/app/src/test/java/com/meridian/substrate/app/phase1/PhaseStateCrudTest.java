package com.meridian.substrate.app.phase1;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Ported homepoc phase1 test. Substrate runs phase1 suites on the 'dev'
// H2-PG-mode profile (Flyway V1+V2, ddl-auto=validate) — the repo-wide test
// convention established by SubstrateApplicationTests; Testcontainers-Postgres
// is the C11 acceptance gate.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class PhaseStateCrudTest extends Phase1StageASupport {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void initializeAndListPhaseStates() throws Exception {
        UUID domainId = createDomain();

        // Initialize — creates one row per LifecyclePhase (7).
        mvc.perform(post("/api/v1/phase-states/initialize").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(7)))
                .andExpect(jsonPath("$.data[0].status", is("NOT_STARTED")));

        mvc.perform(get("/api/v1/phase-states").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(7)));

        // Fetch one phase directly.
        mvc.perform(get("/api/v1/phase-states/{phase}", "DESIGN").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phase", is("DESIGN")))
                .andExpect(jsonPath("$.data.status", is("NOT_STARTED")));
    }
}
