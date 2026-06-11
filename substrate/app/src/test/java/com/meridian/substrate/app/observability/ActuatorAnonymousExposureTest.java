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
 * Stage 3 actuator exposure matrix — anonymous mode (docs/observability.md):
 * with NO Spring Security on the request path, only the health/info probes
 * may exist over the web; metrics is NOT exposed (404), because exposed would
 * mean PUBLIC. The deploy stack's health checks (compose / ALB target group)
 * keep hitting {@code /actuator/health} unauthenticated.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:actuator_anon;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ActuatorAnonymousExposureTest {

    @Autowired MockMvc mvc;

    @Test
    void healthProbeStaysPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void infoStaysPublic() throws Exception {
        mvc.perform(get("/actuator/info")).andExpect(status().isOk());
    }

    @Test
    void metricsIsNotExposedUnderAnonymousMode() throws Exception {
        mvc.perform(get("/actuator/metrics")).andExpect(status().isNotFound());
    }

    @Test
    void namedMeterEndpointIsNotExposedEither() throws Exception {
        mvc.perform(get("/actuator/metrics/jvm.memory.used"))
                .andExpect(status().isNotFound());
    }

    @Test
    void envAndLoggersWereNeverExposed() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/loggers")).andExpect(status().isNotFound());
    }
}
