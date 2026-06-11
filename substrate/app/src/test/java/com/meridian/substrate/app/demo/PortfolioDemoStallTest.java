package com.meridian.substrate.app.demo;

import com.meridian.substrate.app.core.service.PortfolioReadService;
import com.meridian.substrate.app.core.service.read.PortfolioView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D5 acceptance lever, baseline half (engine-spec diagnostic.md §14 step 9):
 * a demo-seeded boot under the NORMAL clock shows the primary demo domain
 * in-Design with ~zero dwell — {@code lifecycleStallCount = 0} — while every
 * Beacon satellite domain (zero phase rows) lands in the SEPARATE
 * {@code unphased} count, never folded into stalls (§12.1). The flip half
 * lives in {@link PortfolioDemoStallFlipTest}.
 */
@SpringBootTest(properties = {
        "substrate.demo.seed=true",
        "spring.datasource.url=jdbc:h2:mem:portfolio_demo_baseline;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("dev")
class PortfolioDemoStallTest {

    @Autowired PortfolioReadService portfolioReadService;

    @Test
    void demoSeedUnderNormalClockShowsZeroStallsAndSatellitesAsUnphased() {
        PortfolioView view = portfolioReadService.portfolio();

        // Demo-seeded boot IS the demo engagement — demo domains form the portfolio.
        assertThat(view.computedFrom().demoDomainsIncluded()).isTrue();
        assertThat(view.subsidiaries()).isNotEmpty();

        // The primary demo domain is in-Design with ~zero dwell: no stall.
        assertThat(view.coordinationSignals().lifecycleStallCount()).isZero();
        PortfolioView.SubsidiaryCard primary = view.subsidiaries().stream()
                .filter(card -> "design".equals(card.lifecyclePhase()))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "PhaseSeeder should leave the primary demo domain in-Design"));
        assertThat(primary.lifecycleStalled()).isFalse();
        assertThat(primary.phaseStatesUninitialized()).isFalse();

        // Beacon satellites have zero phase rows: the SEPARATE unphased count.
        assertThat(view.unphased()).isEqualTo(view.subsidiaries().size() - 1L);
    }
}
