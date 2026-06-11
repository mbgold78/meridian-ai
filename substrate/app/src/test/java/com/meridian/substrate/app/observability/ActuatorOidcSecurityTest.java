package com.meridian.substrate.app.observability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage 3 actuator exposure matrix — oidc mode (docs/observability.md): the
 * metrics endpoints EXIST (exposure computed by
 * {@code ObservabilityEnvironmentPostProcessor}) but sit behind the
 * per-engagement admin roles ({@code substrate.auth.oidc.admin-roles}) via
 * the SAME roles-claim mapping the product admin gates use:
 *
 * <ul>
 *   <li>health/info probes: public (compose / ALB health checks);</li>
 *   <li>metrics unauthenticated → 401;</li>
 *   <li>metrics with a verified non-admin token → 403;</li>
 *   <li>metrics with a verified admin token → 200.</li>
 * </ul>
 *
 * <p>JWT-mock lane (docs/auth.md §7) — no Docker/Keycloak needed; the
 * real-issuer round-trip stays {@code KeycloakOidcRoundTripIT}.
 */
@SpringBootTest(properties = {
        "substrate.auth.mode=oidc",
        "substrate.auth.oidc.persona.role-map.substrate-admin=ADMIN",
        "substrate.auth.oidc.persona.role-map.substrate-exec=EXECUTIVE",
        "spring.datasource.url=jdbc:h2:mem:actuator_oidc;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ActuatorOidcSecurityTest {

    @Autowired MockMvc mvc;

    /** jwt() post-processors inject authentication directly; the decoder is never consulted. */
    @MockitoBean JwtDecoder jwtDecoder;

    @Test
    void healthAndInfoProbesStayPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/info")).andExpect(status().isOk());
    }

    @Test
    void metricsUnauthenticatedIs401() throws Exception {
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    }

    @Test
    void metricsWithNonAdminTokenIs403() throws Exception {
        mvc.perform(get("/actuator/metrics").with(execJwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void metricsWithAdminTokenIs200() throws Exception {
        mvc.perform(get("/actuator/metrics").with(adminJwt()))
                .andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics/jvm.memory.used").with(adminJwt()))
                .andExpect(status().isOk());
    }

    @Test
    void unexposedEndpointsStay404EvenForAdmins() throws Exception {
        mvc.perform(get("/actuator/env").with(adminJwt()))
                .andExpect(status().isNotFound());
    }

    // --------------------------------------------------------------- plumbing

    private RequestPostProcessor adminJwt() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                .claim("name", "Dev Admin")
                .claim("roles", List.of("substrate-admin")));
    }

    private RequestPostProcessor execJwt() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                .claim("name", "Dev Exec")
                .claim("roles", List.of("substrate-exec")));
    }
}
