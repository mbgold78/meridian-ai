package com.meridian.substrate.app.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.repo.DomainRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * JWT-mock lane for {@code substrate.auth.mode=oidc} (docs/auth.md §7 — no
 * Docker/Keycloak needed; the real-issuer round-trip is
 * {@code KeycloakOidcRoundTripIT}):
 *
 * <ul>
 *   <li>unauthenticated {@code /api/**} → 401; {@code /api/v1/health} stays
 *       public;</li>
 *   <li>persona level derives from the VERIFIED roles claim
 *       ({@code persona.role-map});</li>
 *   <li>admin gate consults real roles: {@code substrate-admin} → 201,
 *       non-admin → 403;</li>
 *   <li>S9 severed: a spoofed {@code X-Persona-Level: ADMIN} header changes
 *       NOTHING — neither the envelope persona nor the admin gate.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "substrate.auth.mode=oidc",
        "substrate.auth.oidc.persona.role-map.substrate-admin=ADMIN",
        "substrate.auth.oidc.persona.role-map.substrate-exec=EXECUTIVE",
        "spring.datasource.url=jdbc:h2:mem:oidc_security;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class OidcResourceServerSecurityTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DomainRepository domainRepository;

    /**
     * Replaces {@code OidcSecurityConfig.substrateJwtDecoder} (which would
     * demand a reachable issuer); the {@code jwt()} post-processor injects the
     * authentication directly, so the decoder is never consulted here.
     */
    @MockitoBean JwtDecoder jwtDecoder;

    // ------------------------------------------------------------- 401 / public

    @Test
    void unauthenticatedApiRequestIs401() throws Exception {
        mvc.perform(get("/api/v1/branding/configs").param("domainId", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthProbeStaysPublic() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------ persona from claims

    @Test
    void personaLevelDerivesFromVerifiedRolesClaim() throws Exception {
        mvc.perform(get("/api/v1/health").with(execJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.personaLevel").value("EXECUTIVE"));
    }

    @Test
    void unmappedRolesFallBackToTheDefaultLevel() throws Exception {
        mvc.perform(get("/api/v1/health")
                        .with(jwt().jwt(j -> j.subject("plain-user")
                                .claim("roles", List.of("something-unmapped")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.personaLevel").value("L3_ANALYST"));
    }

    // -------------------------------------------------- admin gate = real roles

    @Test
    void adminRoleSatisfiesTheBrandingAdminGate() throws Exception {
        mvc.perform(post("/api/v1/branding/configs")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(brandingCreateBody()))
                .andExpect(status().isCreated());
    }

    @Test
    void nonAdminRoleIs403AtTheBrandingAdminGate() throws Exception {
        mvc.perform(post("/api/v1/branding/configs")
                        .with(execJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(brandingCreateBody()))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------- S9 spoof is dead

    @Test
    void spoofedAdminPersonaHeaderDoesNotPassTheAdminGate() throws Exception {
        mvc.perform(post("/api/v1/branding/configs")
                        .with(execJwt())
                        .header("X-Persona-Level", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(brandingCreateBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void spoofedPersonaHeadersDoNotInfluenceTheProjectedPersona() throws Exception {
        mvc.perform(get("/api/v1/health")
                        .with(execJwt())
                        .header("X-Persona-Level", "ADMIN")
                        .header("X-Persona-Id", "spoofed-id")
                        .header("X-Persona-Label", "Spoofed Label"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.personaLevel").value("EXECUTIVE"));
    }

    @Test
    void spoofedAdminHeaderWithoutAnyTokenIsStill401() throws Exception {
        mvc.perform(post("/api/v1/branding/configs")
                        .header("X-Persona-Level", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(brandingCreateBody()))
                .andExpect(status().isUnauthorized());
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

    private byte[] brandingCreateBody() throws Exception {
        Domain domain = new Domain();
        domain.setName("OIDC security " + UUID.randomUUID());
        domain.setCreatedBy("test");
        UUID domainId = domainRepository.save(domain).getId();

        ObjectNode body = json.createObjectNode();
        body.put("domainId", domainId.toString());
        body.put("appDisplayName", "Acme Risk");
        body.set("colorTokensLight", json.createObjectNode().put("accent", "#aa0044"));
        body.set("colorTokensDark", json.createObjectNode().put("accent", "#ff5599"));
        body.put("fontBody", "Inter, sans-serif");
        body.put("fontHeading", "Inter, sans-serif");
        body.put("fontMono", "JetBrains Mono, monospace");
        body.set("terminologyOverrides", json.createObjectNode());
        body.put("status", "DRAFT");
        return json.writeValueAsBytes(body);
    }
}
