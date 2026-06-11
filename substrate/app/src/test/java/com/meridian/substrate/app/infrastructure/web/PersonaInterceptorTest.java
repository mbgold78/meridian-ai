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
                new AnonymousIdentitySource());
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
