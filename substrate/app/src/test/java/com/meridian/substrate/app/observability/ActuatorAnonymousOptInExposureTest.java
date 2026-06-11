package com.meridian.substrate.app.observability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage 3 actuator exposure matrix — anonymous mode with the dev-only
 * {@code substrate.observability.actuator.expose=true} opt-in: metrics comes
 * back for local debugging (documented as never-in-a-customer-build,
 * docs/observability.md).
 */
@SpringBootTest(properties = {
        "substrate.observability.actuator.expose=true",
        "spring.datasource.url=jdbc:h2:mem:actuator_optin;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ActuatorAnonymousOptInExposureTest {

    @Autowired MockMvc mvc;

    @Test
    void optInFlagExposesMetricsUnderAnonymousMode() throws Exception {
        mvc.perform(get("/actuator/metrics")).andExpect(status().isOk());
    }

    @Test
    void healthAndInfoRemainPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/info")).andExpect(status().isOk());
    }

    @Test
    void exposureStopsAtMetrics_envStaysClosed() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
    }
}
