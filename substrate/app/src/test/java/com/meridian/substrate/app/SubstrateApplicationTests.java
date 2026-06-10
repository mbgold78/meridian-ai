package com.meridian.substrate.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// Real validate gate (port-map Appendix trap #1): the dev profile applies
// Flyway V1 (engagement baseline) + V2 (canonical-model squash) on H2-PG-mode
// and Hibernate then validates the 26 A1 entities against the migrated schema
// (spring.jpa.hibernate.ddl-auto=validate from application.yml — no override).
// The interim wave-A create-drop override was removed when B2's V2 squash
// landed, per the B2/B3 coordination note in port-map §7.
@SpringBootTest
@ActiveProfiles("dev")
class SubstrateApplicationTests {

    @Test
    void contextLoads() {
    }
}
