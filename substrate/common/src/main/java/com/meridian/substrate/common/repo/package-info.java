/**
 * Unified Spring Data JPA repository layer for the substrate canonical model.
 *
 * <p>Relocated from homepoc's {@code app} module (where repositories lived under
 * {@code core/repository}, {@code process/repository}, {@code capability/repository},
 * {@code branding/repository}, {@code phase/repository}, {@code reader/repository}
 * and {@code ai/assertion}) so that the {@code app}, {@code engine} and
 * {@code ingestion} modules share one repository layer. Homepoc's duplicate
 * {@code Converter*Repository} interfaces (11 bare {@code JpaRepository}
 * extensions in the converter module) are eliminated by this consolidation —
 * ingestion uses these interfaces directly.
 *
 * <p>Boot apps must enable scanning of this package, e.g.
 * {@code @EnableJpaRepositories("com.meridian.substrate")} together with
 * {@code @EntityScan("com.meridian.substrate.common.domain")}.
 */
package com.meridian.substrate.common.repo;
