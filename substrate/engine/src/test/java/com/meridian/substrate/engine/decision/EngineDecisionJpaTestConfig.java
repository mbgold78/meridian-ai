package com.meridian.substrate.engine.decision;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Test-only boot configuration for {@code @DataJpaTest} slices in the engine
 * module's decision package. The engine module is a {@code java-library} with no
 * boot app of its own, so this class provides the {@code @SpringBootConfiguration}
 * anchor that the test slice discovers via package-upward search (same pattern as
 * {@code common}'s {@code RepositoryTestConfig}).
 *
 * <p>Deliberately does NOT use {@code @ComponentScan} — only the JPA entities and
 * the shared {@code common.repo} repositories are bootstrapped. The engine decision
 * services under test are constructed by hand in the tests, which is also what lets
 * the restart-safety test instantiate a "second JVM" service against the same
 * database.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan("com.meridian.substrate.common.domain")
@EnableJpaRepositories("com.meridian.substrate.common.repo")
class EngineDecisionJpaTestConfig {
}
