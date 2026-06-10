package com.meridian.substrate.common.repo;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Test-only boot configuration for {@code @DataJpaTest} slices in the common
 * module. The common module is a {@code java-library} with no boot app of its
 * own, so this class provides the {@code @SpringBootConfiguration} anchor that
 * the test slice discovers via package-upward search.
 *
 * <p>Deliberately does NOT use {@code @ComponentScan} — only the JPA entities
 * and the {@code common.repo} repositories are bootstrapped; the
 * {@code common.ai} beans (LLM client layer) stay out of the slice.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan("com.meridian.substrate.common.domain")
@EnableJpaRepositories("com.meridian.substrate.common.repo")
class RepositoryTestConfig {
}
