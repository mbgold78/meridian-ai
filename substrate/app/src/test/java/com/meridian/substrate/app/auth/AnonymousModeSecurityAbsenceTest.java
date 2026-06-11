package com.meridian.substrate.app.auth;

import com.meridian.substrate.app.infrastructure.auth.AnonymousIdentitySource;
import com.meridian.substrate.app.infrastructure.auth.IdentitySource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Phase 5 anonymous-mode guarantee (docs/auth.md §1): with
 * {@code substrate.auth.mode=anonymous} (the dev default) the
 * oauth2-resource-server classpath addition is INERT —
 * {@code AuthModeEnvironmentPostProcessor} excludes all security
 * auto-configuration, so there is NO filter chain, no CSRF, no security
 * headers; the request pipeline is byte-for-byte the pre-Phase-5 one the rest
 * of the suite asserts against. If this test fails, the entire anonymous
 * regression guarantee is broken.
 */
@SpringBootTest
@ActiveProfiles("dev")
class AnonymousModeSecurityAbsenceTest {

    @Autowired
    ApplicationContext context;

    @Test
    void anonymousModeHasNoSecurityFilterChainAtAll() {
        assertThat(context.containsBean("springSecurityFilterChain"))
                .as("FilterChainProxy must not exist in anonymous mode")
                .isFalse();
        assertThat(context.getBeanNamesForType(SecurityFilterChain.class))
                .as("no SecurityFilterChain beans in anonymous mode")
                .isEmpty();
    }

    @Test
    void anonymousIdentitySourceIsTheActiveLadderRung() {
        assertThat(context.getBean(IdentitySource.class))
                .isInstanceOf(AnonymousIdentitySource.class);
    }
}
