package com.meridian.substrate.app.infrastructure.observability;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 3 actuator-exposure default matrix (docs/observability.md): the
 * computed {@code management.endpoints.web.exposure.include} per auth mode +
 * opt-in flag, and the explicit-config-wins rule. The HTTP-level consequences
 * are pinned by the {@code app.observability.Actuator*Test} classes.
 */
class ObservabilityEnvironmentPostProcessorTest {

    private final ObservabilityEnvironmentPostProcessor processor =
            new ObservabilityEnvironmentPostProcessor();

    @Test
    void anonymousMode_defaultsToHealthAndInfoOnly() {
        MockEnvironment env = new MockEnvironment();
        processor.postProcessEnvironment(env, null);
        assertThat(env.getProperty(ObservabilityEnvironmentPostProcessor.EXPOSURE_PROPERTY))
                .isEqualTo("health,info");
    }

    @Test
    void anonymousMode_optInFlag_addsMetrics() {
        MockEnvironment env = new MockEnvironment()
                .withProperty(ObservabilityEnvironmentPostProcessor.ACTUATOR_EXPOSE_PROPERTY, "true");
        processor.postProcessEnvironment(env, null);
        assertThat(env.getProperty(ObservabilityEnvironmentPostProcessor.EXPOSURE_PROPERTY))
                .isEqualTo("health,info,metrics");
    }

    @Test
    void oidcMode_exposesMetrics_securityChainGatesThem() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("substrate.auth.mode", "oidc");
        processor.postProcessEnvironment(env, null);
        assertThat(env.getProperty(ObservabilityEnvironmentPostProcessor.EXPOSURE_PROPERTY))
                .isEqualTo("health,info,metrics");
    }

    @Test
    void explicitExposureConfigWins_processorDoesNothing() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("substrate.auth.mode", "oidc")
                .withProperty(ObservabilityEnvironmentPostProcessor.EXPOSURE_PROPERTY, "health");
        processor.postProcessEnvironment(env, null);
        assertThat(env.getProperty(ObservabilityEnvironmentPostProcessor.EXPOSURE_PROPERTY))
                .isEqualTo("health");
        assertThat(env.getPropertySources()
                .contains(ObservabilityEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
    }
}
