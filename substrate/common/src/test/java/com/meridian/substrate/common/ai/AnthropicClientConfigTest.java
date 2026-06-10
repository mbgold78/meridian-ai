package com.meridian.substrate.common.ai;

import com.meridian.substrate.common.ai.config.LlmProperties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins degraded-mode boot behavior: API key absent (or blank, or provider != anthropic)
 * => no SDK client bean, context still starts; SdkAnthropicClient registers either way
 * and reports availability truthfully.
 */
class AnthropicClientConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class, AnthropicClientConfig.class,
                    SdkAnthropicClient.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LlmProperties.class)
    static class PropertiesConfig {
    }

    @Test
    void noApiKeyBootsWithoutSdkClientBeanAndClientIsDisabled() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(com.anthropic.client.AnthropicClient.class);
            assertThat(context).hasSingleBean(SdkAnthropicClient.class);
            assertThat(context.getBean(SdkAnthropicClient.class).isAvailable()).isFalse();
        });
    }

    @Test
    void blankApiKeyAlsoDisablesTheSdkClientBean() {
        // mirrors application.yml mapping ${ANTHROPIC_API_KEY:} with the env var unset
        runner.withPropertyValues("substrate.llm.anthropic.api-key=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(com.anthropic.client.AnthropicClient.class);
                    assertThat(context.getBean(SdkAnthropicClient.class).isAvailable()).isFalse();
                });
    }

    @Test
    void presentApiKeyRegistersSdkClientBeanAndEnablesClient() {
        runner.withPropertyValues(
                        "substrate.llm.anthropic.api-key=test-key",
                        "substrate.llm.anthropic.model=claude-test-model")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(com.anthropic.client.AnthropicClient.class);
                    assertThat(context.getBean(SdkAnthropicClient.class).isAvailable()).isTrue();
                });
    }

    @Test
    void nonAnthropicProviderBacksOffEntirely() {
        runner.withPropertyValues(
                        "substrate.llm.provider=mock",
                        "substrate.llm.anthropic.api-key=test-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(com.anthropic.client.AnthropicClient.class);
                    assertThat(context).doesNotHaveBean(SdkAnthropicClient.class);
                });
    }
}
