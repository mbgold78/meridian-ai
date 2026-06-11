package com.meridian.substrate.app.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D5 acceptance lever, flip half (engine-spec diagnostic.md §14 step 9):
 * the SAME DemoPack seed under a {@code substrate.demo.fixed-clock} pinned
 * 120 days into the future flips {@code lifecycleStallCount} 0→1 — the
 * primary demo domain's DESIGN dwell (seeded at wall-clock boot time, §12.1
 * {@code max(enteredAt, updatedAt)}) now exceeds the 90-day threshold. This
 * is the in-CI twin of the user-runnable curl sequence:
 *
 * <pre>
 * gradlew :app:bootRun --args="--spring.profiles.active=dev \
 *   --substrate.demo.seed=true --substrate.demo.fixed-clock=&lt;now+91d&gt;"
 * curl :8080/api/v1/portfolio   # → coordinationSignals.lifecycleStallCount: 1
 * </pre>
 *
 * <p>Asserted through the REST surface (the curl shape), not the service —
 * the lever must be demonstrable to a human with a terminal.
 */
@SpringBootTest(properties = {
        "substrate.demo.seed=true",
        "spring.datasource.url=jdbc:h2:mem:portfolio_demo_flip;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class PortfolioDemoStallFlipTest {

    /** Pin engine "now" 120d ahead of the wall-clock seed instant (>90d threshold). */
    @DynamicPropertySource
    static void pinClockBeyondTheStallThreshold(DynamicPropertyRegistry registry) {
        registry.add("substrate.demo.fixed-clock",
                () -> Instant.now().plus(Duration.ofDays(120)).toString());
    }

    @Autowired MockMvc mvc;

    @Test
    void fixedClockFlipsTheStallCountToOne() throws Exception {
        mvc.perform(get("/api/v1/portfolio"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coordinationSignals.lifecycleStallCount", is(1)))
                // The satellites stay a SEPARATE unphased count — the flip
                // changes the stall term only (§12.1: never folded together).
                .andExpect(jsonPath("$.data.unphased", greaterThanOrEqualTo(1)));
    }
}
