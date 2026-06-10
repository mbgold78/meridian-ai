package com.meridian.substrate.app.phase1;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Flag-ON leg of the S4 fixture seam: with
 * {@code substrate.transformation.fixture-enabled=true} the endpoint serves the
 * homepoc transformation-plan mockup verbatim. The default (flag off → 404) is
 * asserted in C1EndpointInventoryTest against the unmodified context.
 */
@SpringBootTest(properties = "substrate.transformation.fixture-enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class TransformationPlanFixtureEnabledTest {

    @Autowired MockMvc mvc;

    @Test
    void servesFixtureWhenFlagEnabled() throws Exception {
        mvc.perform(get("/api/v1/transformation/plan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", notNullValue()));
    }
}
