package com.meridian.substrate.app.postgres;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C11 Phase-1 close gate — Flyway full-apply against real Postgres 16
 * (port-map §4.4 / Appendix trap #4: H2-green is never the acceptance gate).
 *
 * <p>Booting this context already IS the primary assertion: Flyway applies
 * V1 (engagement baseline) + V2 (canonical-model squash) + the Postgres-only
 * vendor migration V2.1 (partial unique index) + V3 (ingest_runs), and
 * Hibernate then validates all 27 entities against the migrated schema
 * ({@code spring.jpa.hibernate.ddl-auto=validate}, no test override).
 * The tests below additionally pin the schema-history contents and the
 * DB-level single-ACTIVE branding invariant that only exists on Postgres.
 *
 * <p><b>Docker-less machines (e.g. the Windows dev box):</b> the whole class
 * self-disables via {@code @Testcontainers(disabledWithoutDocker = true)} —
 * `gradlew build` stays green locally and CI (ubuntu-latest, Docker present)
 * runs the real gate.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class PostgresFlywayMigrationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayFullApplyIncludesCommonAndPostgresVendorMigrations() {
        List<Map<String, Object>> history = jdbcTemplate.queryForList(
                "SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");

        // Vendor-split resolution (§4.2): {vendor} → postgresql, so the
        // dot-versioned vendor migrations MUST apply here (they must NOT on
        // H2 — pinned by the dev-profile FlywayMigrationTest). V7/V7.1 =
        // diagnostic engine (engine-spec diagnostic.md §3.1/§3.2).
        assertThat(history)
                .extracting(row -> String.valueOf(row.get("version")))
                .contains("1", "2", "2.1", "3", "7", "7.1");
        assertThat(history)
                .allSatisfy(row -> assertThat(row.get("success"))
                        .as("migration V%s succeeded", row.get("version"))
                        .isEqualTo(Boolean.TRUE));
    }

    @Test
    void allCanonicalTablesExistOnPostgres() {
        List<String> expected = List.of(
                "engagement",
                "domains", "systems", "processes", "process_steps", "process_roles",
                "process_system_links", "organization_members", "canonical_entities",
                "entity_overlaps", "integrations", "role_overlaps", "findings",
                "design_decisions", "synthesis_runs", "chat_interactions",
                "document_registry", "evidence", "assertions", "system_uploads",
                "capabilities", "capability_name_history", "capability_process_links",
                "capability_system_links", "phase_states", "reader_registrations",
                "branding_configs", "ingest_runs",
                // V7 diagnostic engine (engine-spec diagnostic.md §3.1)
                "ai_curation_events", "diagnostic_run_batches");

        List<String> actual = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(actual).containsAll(expected);
    }

    @Test
    void activeBrandingPartialUniqueIndexExists() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE tablename = 'branding_configs' "
                        + "AND indexname = 'uq_branding_configs_active_per_domain'",
                Integer.class);
        assertThat(count)
                .as("V2_1 partial unique index applied on Postgres")
                .isEqualTo(1);
    }

    /**
     * The one-ACTIVE-per-domain invariant is service-enforced everywhere, but
     * ONLY Postgres backstops it at the DB level (H2 rejects partial indexes —
     * port-map §4.3). This is the behavioral pin that can never run on H2.
     */
    @Test
    void duplicateActiveBrandingConfigRejectedByPartialIndex_draftsMayCoexist() {
        UUID domainId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO domains (id, name, is_demo, created_at, updated_at, created_by, version) "
                        + "VALUES (?, ?, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'c11-it', 0)",
                domainId, "C11 Partial Index Domain");

        insertBrandingConfig(domainId, "ACTIVE");   // first ACTIVE — fine
        insertBrandingConfig(domainId, "DRAFT");    // DRAFT coexists — index is partial
        insertBrandingConfig(domainId, "DRAFT");    // multiple DRAFTs — fine

        assertThatThrownBy(() -> insertBrandingConfig(domainId, "ACTIVE"))
                .as("second ACTIVE row for the same domain violates the partial unique index")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_branding_configs_active_per_domain");
    }

    private void insertBrandingConfig(UUID domainId, String status) {
        jdbcTemplate.update(
                "INSERT INTO branding_configs (id, domain_id, app_display_name, "
                        + "color_tokens_light, color_tokens_dark, font_body, font_heading, font_mono, "
                        + "terminology_overrides, status, created_at, updated_at, created_by, version) "
                        + "VALUES (?, ?, 'C11 IT', '{}', '{}', 'Inter', 'Inter', 'JetBrains Mono', "
                        + "'{}', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'c11-it', 0)",
                UUID.randomUUID(), domainId, status);
    }
}
