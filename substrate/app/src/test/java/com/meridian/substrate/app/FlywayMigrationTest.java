package com.meridian.substrate.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway gate for the dev profile (H2 MODE=PostgreSQL): V1 (engagement
 * baseline) + V2 (canonical-model squash) must apply cleanly, and the
 * vendor-split locations must resolve so that the Postgres-only V2.1 partial
 * index is NOT applied on H2 (port-map §4.2/§4.3).
 *
 * <p>Boot itself already proves ddl-auto=validate passes against the migrated
 * schema (same context as {@link SubstrateApplicationTests} — identical
 * annotations, shared Spring test context). This test additionally pins the
 * schema-history contents and the existence of the key tables.
 *
 * <p>H2-green is not the Postgres acceptance gate — the Testcontainers
 * suite (chunk C11) remains the dialect authority (port-map §4.4).
 */
@SpringBootTest
@ActiveProfiles("dev")
class FlywayMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void v1AndV2AppliedSuccessfully_andPostgresVendorMigrationStaysOff() {
        List<Map<String, Object>> history = jdbcTemplate.queryForList(
                "SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");

        assertThat(history)
                .extracting(row -> String.valueOf(row.get("version")))
                .contains("1", "2", "4");
        assertThat(history)
                .allSatisfy(row -> assertThat(row.get("success"))
                        .as("migration V%s succeeded", row.get("version"))
                        .isEqualTo(Boolean.TRUE));
        // Vendor split: db/migration/postgresql/V2_1 (partial index) and V4_1
        // (cascade_level_actions append-only trigger) must not run on the H2
        // dev profile — H2 stays service-enforced (ADR-001 D9).
        assertThat(history)
                .extracting(row -> String.valueOf(row.get("version")))
                .doesNotContain("2.1", "4.1");
    }

    @Test
    void keyTablesExist() {
        // V1 baseline + representative V2 canonical-model tables (post-V8 names:
        // processes / process_roles / process_system_links — never manual_*).
        List<String> expected = List.of(
                "engagement",
                "domains", "systems", "processes", "process_steps", "process_roles",
                "process_system_links", "organization_members", "canonical_entities",
                "entity_overlaps", "integrations", "role_overlaps", "findings",
                "design_decisions", "synthesis_runs", "chat_interactions",
                "document_registry", "evidence", "assertions", "system_uploads",
                "capabilities", "capability_name_history", "capability_process_links",
                "capability_system_links", "phase_states", "reader_registrations",
                "branding_configs",
                // V4 cascade engine (engine-spec cascade.md §3)
                "cascades", "cascade_levels", "cascade_level_actions",
                "cascade_drafts", "cascade_notifications");

        List<String> actual = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(actual).containsAll(expected);
    }
}
