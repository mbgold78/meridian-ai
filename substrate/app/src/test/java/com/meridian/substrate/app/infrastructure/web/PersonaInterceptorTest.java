package com.meridian.substrate.app.infrastructure.web;

import com.meridian.substrate.app.infrastructure.auth.AnonymousIdentitySource;
import com.meridian.substrate.app.infrastructure.auth.SubstrateIdentityContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anonymous-mode identity behavior (Phase 5: the interceptor now projects the
 * active IdentitySource onto PersonaContext — with {@code
 * AnonymousIdentitySource}, header semantics are byte-for-byte pre-Phase-5).
 */
class PersonaInterceptorTest {

    private static PersonaInterceptor anonymousInterceptor(PersonaContext context) {
        return new PersonaInterceptor(context, new SubstrateIdentityContext(),
                new AnonymousIdentitySource(
                        new com.meridian.substrate.app.infrastructure.auth.AuthProperties()));
    }

    @Test
    void streamPath_fallsBackToPersonaLevelQueryParam_whenHeaderAbsent() {
        // The SSE carve-out: EventSource cannot set headers, so the configured
        // stream paths accept ?personaLevel= (mirror of the access_token
        // carve-out). Everywhere else the param stays dead.
        PersonaContext context = new PersonaContext();
        PersonaInterceptor interceptor = anonymousInterceptor(context);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/chat/stream");
        request.setParameter("personaLevel", "L1_EXECUTIVE");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isTrue();
        assertThat(context.getPersonaLevel()).isEqualTo("L1_EXECUTIVE");

        // non-stream path: the query param is ignored, default applies
        PersonaContext other = new PersonaContext();
        MockHttpServletRequest nonStream = new MockHttpServletRequest();
        nonStream.setRequestURI("/api/v1/chat");
        nonStream.setParameter("personaLevel", "L1_EXECUTIVE");
        anonymousInterceptor(other).preHandle(nonStream, new MockHttpServletResponse(), new Object());
        assertThat(other.getPersonaLevel()).isEqualTo("L3_ANALYST");

        // header still wins on stream paths when present
        PersonaContext headerWins = new PersonaContext();
        MockHttpServletRequest both = new MockHttpServletRequest();
        both.setRequestURI("/api/v1/chat/stream");
        both.addHeader("X-Persona-Level", "L2_DOMAIN_LEAD");
        both.setParameter("personaLevel", "L1_EXECUTIVE");
        anonymousInterceptor(headerWins).preHandle(both, new MockHttpServletResponse(), new Object());
        assertThat(headerWins.getPersonaLevel()).isEqualTo("L2_DOMAIN_LEAD");
    }

    @Test
    void populatesContextFromHeaders() {
        PersonaContext context = new PersonaContext();
        PersonaInterceptor interceptor = anonymousInterceptor(context);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Persona-Level", "L1_EXECUTIVE");
        request.addHeader("X-Persona-Id", "user-42");

        boolean proceed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(proceed).isTrue();
        assertThat(context.getPersonaLevel()).isEqualTo("L1_EXECUTIVE");
        assertThat(context.getPersonaId()).isEqualTo("user-42");
    }

    @Test
    void missingHeaders_keepDefaultLevelAndNullId() {
        PersonaContext context = new PersonaContext();
        PersonaInterceptor interceptor = anonymousInterceptor(context);

        boolean proceed = interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), new Object());

        assertThat(proceed).isTrue();
        assertThat(context.getPersonaLevel()).isEqualTo("L3_ANALYST");
        assertThat(context.getPersonaId()).isNull();
    }

    @Test
    void snapshotIsDetachedCopy() {
        PersonaContext context = new PersonaContext();
        context.setPersonaLevel("L2_ARCHITECT");
        context.setPersonaId("user-7");

        PersonaContext snapshot = context.snapshot();
        context.setPersonaLevel("L3_ANALYST");
        context.setPersonaId(null);

        assertThat(snapshot.getPersonaLevel()).isEqualTo("L2_ARCHITECT");
        assertThat(snapshot.getPersonaId()).isEqualTo("user-7");
    }
}
