package com.meridian.substrate.engine.contract;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Test-only boot configuration for {@code @DataJpaTest} slices in the engine
 * module's contract package — same pattern as
 * {@code engine.cascade.EngineCascadeJpaTestConfig} /
 * {@code engine.decision.EngineDecisionJpaTestConfig}: the engine module is a
 * {@code java-library} with no boot app of its own, so this class provides the
 * {@code @SpringBootConfiguration} anchor the slice discovers via
 * package-upward search (found before {@code EngineTestApplication} one
 * package up, which deliberately does not scan {@code engine.contract}).
 *
 * <p>Deliberately NO {@code @ComponentScan}: only the shared entities and
 * repositories are bootstrapped. The contract services under test are
 * constructed by hand; verbs are driven through explicit
 * {@code TransactionTemplate} transactions (PESSIMISTIC_WRITE needs an active
 * transaction; hand-constructed services have no {@code @Transactional}
 * proxies).
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan("com.meridian.substrate.common.domain")
@EnableJpaRepositories("com.meridian.substrate.common.repo")
class EngineContractJpaTestConfig {
}
