package com.meridian.substrate.app.infrastructure.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * The anonymous-mode "byte-for-byte" guarantee (Phase 5 hard constraint):
 * adding {@code spring-boot-starter-oauth2-resource-server} to the classpath
 * would otherwise let Boot's security auto-configuration install a
 * deny-everything default filter chain (+ CSRF, security headers) and break
 * every pre-Phase-5 behavior the 1000+-test suite pins.
 *
 * <p>This post-processor runs after config-data loading (profile YAMLs
 * included, hence {@code LOWEST_PRECEDENCE}); whenever
 * {@code substrate.auth.mode} is NOT {@code oidc} it excludes ALL security
 * auto-configuration, leaving the servlet pipeline EXACTLY as it was before
 * the dependency existed. Under {@code oidc} it does nothing and Boot wires
 * the standard security plumbing around {@link OidcSecurityConfig}'s filter
 * chain.
 *
 * <p>Registered via {@code META-INF/spring.factories}; it also runs for every
 * {@code @SpringBootTest} bootstrap, where inlined
 * {@code properties = "substrate.auth.mode=oidc"} are already visible in the
 * environment — so oidc test slices get real security while the untouched
 * regression suite gets none.
 */
public class AuthModeEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "substrateAnonymousAuthExclusions";

    static final String[] SECURITY_AUTO_CONFIGURATIONS = {
            "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration",
            "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration",
            "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration",
            "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration"
    };

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                       SpringApplication application) {
        String mode = environment.getProperty("substrate.auth.mode", AuthProperties.MODE_ANONYMOUS);
        if (AuthProperties.MODE_OIDC.equalsIgnoreCase(mode.trim())) {
            return;
        }
        String exclusions = String.join(",", SECURITY_AUTO_CONFIGURATIONS);
        String existing = environment.getProperty("spring.autoconfigure.exclude");
        if (existing != null && !existing.isBlank()) {
            exclusions = existing + "," + exclusions;
        }
        environment.getPropertySources().addFirst(new MapPropertySource(
                PROPERTY_SOURCE_NAME, Map.of("spring.autoconfigure.exclude", exclusions)));
    }

    @Override
    public int getOrder() {
        // After ConfigDataEnvironmentPostProcessor so profile YAMLs
        // (application-dev.yml's mode: anonymous) are already loaded.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
