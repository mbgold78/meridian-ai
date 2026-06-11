package com.meridian.substrate.app.infrastructure.observability;

import com.meridian.substrate.app.infrastructure.auth.AuthProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Stage 3 actuator lockdown (plan §8): computes the per-auth-mode default for
 * {@code management.endpoints.web.exposure.include} so the management surface
 * is never wider than the security posture protecting it.
 *
 * <ul>
 *   <li>{@code anonymous} (no Spring Security on the request path at all —
 *       see {@link com.meridian.substrate.app.infrastructure.auth.AuthModeEnvironmentPostProcessor}):
 *       only {@code health,info} exist over the web. Anything more would be
 *       a PUBLIC endpoint. {@code substrate.observability.actuator.expose=true}
 *       opts metrics back in for local debugging — documented as dev-only.</li>
 *   <li>{@code oidc}: {@code health,info,metrics} are exposed; everything
 *       beyond health/info is gated on the engagement's admin roles by
 *       {@code OidcSecurityConfig} (deny happens at the filter chain, so the
 *       endpoint may safely exist).</li>
 * </ul>
 *
 * <p><b>Explicit config wins:</b> when
 * {@code management.endpoints.web.exposure.include} is already present
 * anywhere in the environment (env var, command line, profile YAML), this
 * post-processor does nothing — engagements keep full control. The base
 * {@code application.yml} deliberately does NOT set the key.
 *
 * <p>Registered via {@code META-INF/spring.factories}; runs after config-data
 * loading ({@code LOWEST_PRECEDENCE}) so profile YAMLs and inlined
 * {@code @SpringBootTest} properties are already visible.
 */
public class ObservabilityEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "substrateActuatorExposureDefaults";

    static final String EXPOSURE_PROPERTY = "management.endpoints.web.exposure.include";
    static final String ACTUATOR_EXPOSE_PROPERTY = "substrate.observability.actuator.expose";

    static final String PUBLIC_ONLY = "health,info";
    static final String WITH_METRICS = "health,info,metrics";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                       SpringApplication application) {
        if (environment.getProperty(EXPOSURE_PROPERTY) != null) {
            return;                                     // explicit engagement config wins
        }
        String mode = environment.getProperty("substrate.auth.mode", AuthProperties.MODE_ANONYMOUS);
        boolean oidc = AuthProperties.MODE_OIDC.equalsIgnoreCase(mode.trim());
        boolean optIn = environment.getProperty(ACTUATOR_EXPOSE_PROPERTY, Boolean.class, false);
        String include = (oidc || optIn) ? WITH_METRICS : PUBLIC_ONLY;
        environment.getPropertySources().addLast(new MapPropertySource(
                PROPERTY_SOURCE_NAME, Map.of(EXPOSURE_PROPERTY, include)));
    }

    @Override
    public int getOrder() {
        // After ConfigDataEnvironmentPostProcessor — profile YAMLs (e.g. the
        // postgres profile's oidc mode) must already be loaded.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
