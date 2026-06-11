package com.meridian.substrate.app.auth;

import com.meridian.substrate.app.core.service.ChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * SSE token transport under oidc (docs/auth.md §6): browser
 * {@code EventSource} cannot set an {@code Authorization} header, so the chat
 * stream GET accepts the RFC 6750 §2.3 {@code access_token} query parameter —
 * and ONLY the configured stream paths do (the carve-out must never widen
 * token-in-URL exposure to the rest of the API).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "substrate.auth.mode=oidc",
        "spring.datasource.url=jdbc:h2:mem:oidc_sse;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("dev")
class OidcSseTokenResolutionTest {

    private static final String TOKEN = "sse-token";

    @Autowired TestRestTemplate rest;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean ChatService chatService;

    @BeforeEach
    void setUp() {
        when(jwtDecoder.decode(TOKEN)).thenReturn(Jwt.withTokenValue(TOKEN)
                .header("alg", "none")
                .subject("c1d2e3f4-0a1b-2c3d-4e5f-6a7b8c9d0e1f")
                .claim("name", "Stream User")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(Duration.ofHours(1)))
                .build());
        when(chatService.chatStream(any(), any(), any())).thenReturn(Flux.empty());
    }

    @Test
    void streamWithoutAnyTokenIs401() {
        ResponseEntity<String> response = rest.getForEntity(
                "/api/v1/chat/stream?domainId={d}&message=hi", String.class, UUID.randomUUID());
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void streamAcceptsTheRfc6750AccessTokenQueryParameter() {
        ResponseEntity<String> response = rest.getForEntity(
                "/api/v1/chat/stream?domainId={d}&message=hi&access_token=" + TOKEN,
                String.class, UUID.randomUUID());
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("EventSource lane: token via query param (got %s)", response.getStatusCode())
                .isTrue();
    }

    @Test
    void streamStillAcceptsTheAuthorizationHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TOKEN);
        ResponseEntity<String> response = rest.exchange(
                "/api/v1/chat/stream?domainId={d}&message=hi", HttpMethod.GET,
                new HttpEntity<>(headers), String.class, UUID.randomUUID());
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void accessTokenQueryParameterIsRejectedOffTheSsePaths() {
        ResponseEntity<String> response = rest.getForEntity(
                "/api/v1/branding/configs?domainId={d}&access_token=" + TOKEN,
                String.class, UUID.randomUUID());
        assertThat(response.getStatusCode().value())
                .as("token-in-URL is confined to the configured SSE paths")
                .isEqualTo(401);
    }
}
