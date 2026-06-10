package com.meridian.substrate.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Test-only boot application for the engine module. The engine is a
 * {@code java-library} (the real boot app lives in {@code :app}), so engine
 * integration tests stand up their own minimal context: common entities +
 * repositories on H2 (create-drop) and every engine bean, with
 * {@code substrate.llm.provider=mock} (see {@code application-test.yml}).
 *
 * <p>No web layer — the phase2a behavioral-contract tests ported from homepoc
 * exercise the mode services directly; the MockMvc-level coverage over the
 * {@code /api/v1/ai/*} controllers is re-added in chunk C5 in the app module.
 *
 * <p>Scan is limited to chunk C3's packages ({@code common} config/beans +
 * {@code engine.ai} + {@code engine.dna}): chunk C4's decision package carries
 * its own test-slice {@code @SpringBootConfiguration}
 * ({@code engine.decision.EngineDecisionJpaTestConfig}); a whole-tree scan would
 * pick it up from the shared test classpath and double-register the JPA
 * repositories.
 */
@SpringBootApplication(scanBasePackages = {
        "com.meridian.substrate.common",
        "com.meridian.substrate.engine.ai",
        "com.meridian.substrate.engine.dna"
})
@EntityScan(basePackages = "com.meridian.substrate.common.domain")
@EnableJpaRepositories(basePackages = "com.meridian.substrate.common.repo")
public class EngineTestApplication {

    /**
     * Boot's Jackson auto-configuration only contributes an {@link ObjectMapper}
     * bean when spring-web's {@code Jackson2ObjectMapperBuilder} is on the
     * classpath — engine is web-free, so the test context provides its own
     * (the real bean comes from the app module's web starter at runtime).
     */
    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
