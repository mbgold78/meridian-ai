package com.meridian.substrate.engine.cascade;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Test-only boot configuration for {@code @DataJpaTest} slices in the engine
 * module's cascade package — same pattern as
 * {@code engine.decision.EngineDecisionJpaTestConfig}: the engine module is a
 * {@code java-library} with no boot app of its own, so this class provides the
 * {@code @SpringBootConfiguration} anchor the slice discovers via
 * package-upward search (found before {@code EngineTestApplication} one
 * package up, which deliberately does not scan {@code engine.cascade}).
 *
 * <p>Deliberately NO {@code @ComponentScan}: only the shared entities and
 * repositories are bootstrapped. The cascade engine services under test are
 * constructed by hand in the tests — verbs are then driven through explicit
 * {@code TransactionTemplate} transactions, which is what lets the outbox
 * same-transaction test observe commit/rollback atomicity directly.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan("com.meridian.substrate.common.domain")
@EnableJpaRepositories("com.meridian.substrate.common.repo")
class EngineCascadeJpaTestConfig {
}
