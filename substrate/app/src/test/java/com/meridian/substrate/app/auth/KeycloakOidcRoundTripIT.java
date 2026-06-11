package com.meridian.substrate.app.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.repo.DomainRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-issuer OIDC round-trip (production plan §4: "auth flows are real, IdP
 * is throwaway"): boots the SAME Keycloak image docker-compose uses, imports
 * the scaffolded {@code infra/keycloak/realm-substrate.json}, obtains REAL
 * password-grant tokens, and proves:
 *
 * <ul>
 *   <li>issuer discovery + signature verification: a Keycloak-issued token is
 *       accepted by the API;</li>
 *   <li>persona maps from the realm's {@code realm_access.roles} claim;</li>
 *   <li>admin role honored: {@code dev-admin} (substrate-admin) can write
 *       branding config, {@code dev-coo} cannot (403);</li>
 *   <li>no token → 401.</li>
 * </ul>
 *
 * <p>Self-disables without Docker ({@code disabledWithoutDocker = true}) —
 * the dev box has no Docker; CI (ubuntu-latest, {@code ./gradlew build}) is
 * the real gate, exactly like the Postgres IT lane (port-map §4.4).
 * NOTHING is provisioned anywhere: the IdP lives and dies inside the test.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "substrate.auth.mode=oidc",
        "substrate.auth.oidc.claims.roles=realm_access.roles",
        "substrate.auth.oidc.persona.role-map.substrate-admin=ADMIN",
        "substrate.auth.oidc.persona.role-map.substrate-coo=EXECUTIVE",
        "spring.datasource.url=jdbc:h2:mem:kc_oidc;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("dev")
class KeycloakOidcRoundTripIT {

    /** Same image + import mechanism as docker-compose.yml. */
    @Container
    static final GenericContainer<?> KEYCLOAK =
            new GenericContainer<>("quay.io/keycloak/keycloak:26.0")
                    .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
                    .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
                    // pre-26 env names, harmless on 26 — keeps the image pin movable
                    .withEnv("KEYCLOAK_ADMIN", "admin")
                    .withEnv("KEYCLOAK_ADMIN_PASSWORD", "admin")
                    .withCopyFileToContainer(MountableFile.forHostPath(realmFile()),
                            "/opt/keycloak/data/import/realm-substrate.json")
                    .withCommand("start-dev", "--import-realm")
                    .withExposedPorts(8080)
                    .waitingFor(Wait.forHttp("/realms/substrate/.well-known/openid-configuration")
                            .forPort(8080)
                            .withStartupTimeout(Duration.ofMinutes(5)));

    @DynamicPropertySource
    static void issuer(DynamicPropertyRegistry registry) {
        registry.add("substrate.auth.oidc.issuer-uri", KeycloakOidcRoundTripIT::issuerUri);
    }

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper json;
    @Autowired DomainRepository domainRepository;

    // ---------------------------------------------------------------- tests

    @Test
    void keycloakTokenIsAcceptedAndPersonaMapsFromRealmRoles() {
        ResponseEntity<String> response = withBearer(passwordGrantToken("dev-admin", "dev-admin"))
                .exchange("/api/v1/health");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(readTree(response.getBody()).path("meta").path("personaLevel").asText())
                .as("persona derives from realm_access.roles (substrate-admin → ADMIN)")
                .isEqualTo("ADMIN");
    }

    @Test
    void adminRealmRoleIsHonoredAtTheBrandingGate() {
        ResponseEntity<String> created = withBearer(passwordGrantToken("dev-admin", "dev-admin"))
                .post("/api/v1/branding/configs", brandingCreateBody());
        assertThat(created.getStatusCode().value())
                .as("dev-admin (substrate-admin) can write branding config: %s",
                        created.getBody())
                .isEqualTo(201);
    }

    @Test
    void nonAdminRealmRoleIs403AtTheBrandingGate() {
        ResponseEntity<String> denied = withBearer(passwordGrantToken("dev-coo", "dev-coo"))
                .post("/api/v1/branding/configs", brandingCreateBody());
        assertThat(denied.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void noTokenIs401() {
        ResponseEntity<String> response = rest.getForEntity(
                "/api/v1/branding/configs?domainId=" + UUID.randomUUID(), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    // ------------------------------------------------------------- plumbing

    static String issuerUri() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080)
                + "/realms/substrate";
    }

    /** The scaffolded realm, resolved relative to the module/repo layout. */
    static Path realmFile() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("infra").resolve("keycloak")
                    .resolve("realm-substrate.json");
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "infra/keycloak/realm-substrate.json not found above " + System.getProperty("user.dir"));
    }

    /** Real password-grant token from the imported realm's public client. */
    private String passwordGrantToken(String username, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", "substrate-app");
        form.add("username", username);
        form.add("password", password);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<String> response = new RestTemplate().postForEntity(
                issuerUri() + "/protocol/openid-connect/token",
                new HttpEntity<>(form, headers), String.class);
        return readTree(response.getBody()).path("access_token").asText();
    }

    private BearerCalls withBearer(String token) {
        return new BearerCalls(token);
    }

    private final class BearerCalls {
        private final HttpHeaders headers = new HttpHeaders();

        private BearerCalls(String token) {
            headers.setBearerAuth(token);
            headers.setContentType(MediaType.APPLICATION_JSON);
        }

        ResponseEntity<String> exchange(String path) {
            return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        }

        ResponseEntity<String> post(String path, Object body) {
            return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers),
                    String.class);
        }
    }

    private ObjectNode brandingCreateBody() {
        Domain domain = new Domain();
        domain.setName("Keycloak OIDC IT " + UUID.randomUUID());
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
        return body;
    }

    private JsonNode readTree(String body) {
        try {
            return json.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("unparseable JSON: " + body, e);
        }
    }
}
