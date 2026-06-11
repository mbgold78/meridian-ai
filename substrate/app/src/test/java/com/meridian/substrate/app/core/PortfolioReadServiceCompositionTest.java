package com.meridian.substrate.app.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.app.config.StatsProperties;
import com.meridian.substrate.app.core.service.PortfolioReadService;
import com.meridian.substrate.app.core.service.read.PortfolioView;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.repo.CapabilityRepository;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.common.repo.FindingRepository;
import com.meridian.substrate.common.repo.PhaseStateRepository;
import com.meridian.substrate.common.repo.ProcessRepository;
import com.meridian.substrate.common.repo.SystemRepository;
import com.meridian.substrate.common.repo.TransformationInitiativeRepository;
import com.meridian.substrate.common.repo.TransformationPlanRepository;
import com.meridian.substrate.engine.agent.AgentQueryService;
import com.meridian.substrate.engine.agent.read.AgentSignals;
import com.meridian.substrate.engine.cascade.CascadeQueryService;
import com.meridian.substrate.engine.cascade.read.CascadeStats;
import com.meridian.substrate.engine.contract.read.SignalsResponse;
import com.meridian.substrate.engine.contract.registry.ContractReadService;
import com.meridian.substrate.engine.contract.registry.ContractSignalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D5 composition pins (engine-spec diagnostic.md §12.2/§12.3/§12.7; ADR-004
 * D13; test plan #14):
 *
 * <ul>
 *   <li><b>Delegation by construction:</b> the portfolio's
 *       {@code coordinationPatternsThisWeek} / per-subsidiary
 *       {@code pendingFederationInvitations} ARE the numbers the injected
 *       {@link ContractSignalService} bean returned — same bean, one
 *       computation, asserted number == number;</li>
 *   <li><b>Absent-vs-zero DTO shape:</b> a missing D3 registry serializes
 *       {@code agents.available=false} with NULL counts — structurally
 *       distinct from a present registry reporting real zeros;</li>
 *   <li><b>Demo isolation:</b> {@code isDemo} domains never enter a
 *       production-posture portfolio (demoDomainsIncluded=false).</li>
 * </ul>
 */
class PortfolioReadServiceCompositionTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC);

    private final DomainRepository domains = mock(DomainRepository.class);
    private final PhaseStateRepository phaseStates = mock(PhaseStateRepository.class);
    private final FindingRepository findings = mock(FindingRepository.class);
    private final CapabilityRepository capabilities = mock(CapabilityRepository.class);
    private final ProcessRepository processes = mock(ProcessRepository.class);
    private final SystemRepository systems = mock(SystemRepository.class);
    private final TransformationPlanRepository plans = mock(TransformationPlanRepository.class);
    private final TransformationInitiativeRepository initiatives =
            mock(TransformationInitiativeRepository.class);
    private final ContractSignalService signalService = mock(ContractSignalService.class);
    private final ContractReadService contractReads = mock(ContractReadService.class);
    private final CascadeQueryService cascades = mock(CascadeQueryService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<AgentQueryService> agentProvider = mock(ObjectProvider.class);

    private final Domain alpha = domain("Alpha", false);
    private final Domain beta = domain("Beta", false);
    private final Domain demo = domain("Demo", true);

    private static Domain domain(String name, boolean isDemo) {
        Domain d = new Domain();
        d.setId(UUID.randomUUID());
        d.setName(name);
        d.setDemo(isDemo);
        d.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return d;
    }

    private static SignalsResponse signals(long federation, long patterns,
                                           long contractEvents, long cascadeActions) {
        return new SignalsResponse(federation,
                new SignalsResponse.OpenBreaches(0, Map.of()),
                List.of(), List.of(),
                new SignalsResponse.MonitorCoverage(0, 0),
                patterns, List.of(),
                new SignalsResponse.CoordinationBreakdown(contractEvents, cascadeActions));
    }

    private static CascadeStats cascadeStats(UUID domainId, long declinePending) {
        return new CascadeStats(domainId, 0,
                Map.of(), Map.of("decline-pending", declinePending), 0, 0);
    }

    private PortfolioReadService service(boolean demoIncluded) {
        return new PortfolioReadService(domains, phaseStates, findings, capabilities,
                processes, systems, plans, initiatives, signalService, contractReads,
                cascades, agentProvider, new StatsProperties(), FIXED,
                "Substrate Engagement", demoIncluded);
    }

    @BeforeEach
    void stubDefaults() {
        when(domains.findAll()).thenReturn(List.of(alpha, beta, demo));
        when(phaseStates.findByDomainId(any())).thenReturn(List.of());
        when(findings.findByDomainId(any())).thenReturn(List.of());
        when(contractReads.cards(any(), any(), any())).thenReturn(List.of());
        when(signalService.signals(alpha.getId())).thenReturn(signals(2, 5, 3, 2));
        when(signalService.signals(beta.getId())).thenReturn(signals(1, 4, 0, 4));
        when(cascades.stats(alpha.getId())).thenReturn(cascadeStats(alpha.getId(), 1));
        when(cascades.stats(beta.getId())).thenReturn(cascadeStats(beta.getId(), 2));
    }

    @Test
    void coordinationNumbersAreTheSignalServiceNumbers_byConstruction() {
        when(agentProvider.getIfAvailable()).thenReturn(null);

        PortfolioView view = service(false).portfolio();

        // number == number: Σ of exactly what the §11.2 bean returned (5+4),
        // with the per-source sub-counts it already owns (3+0 / 2+4).
        assertThat(view.coordinationSignals().coordinationPatternsThisWeek()).isEqualTo(9);
        assertThat(view.coordinationSignals().coordinationBreakdown())
                .isEqualTo(new SignalsResponse.CoordinationBreakdown(3, 6));
        assertThat(view.coordinationSignals().pendingFederationInvitations()).isEqualTo(3);
        // Order ties on createdAt break by random UUID — assert per-domain, not by position.
        assertThat(view.subsidiaries())
                .extracting(PortfolioView.SubsidiaryCard::name,
                        PortfolioView.SubsidiaryCard::pendingFederationInvitations)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("Alpha", 2L),
                        org.assertj.core.groups.Tuple.tuple("Beta", 1L));
        // D1 attention feed: decline-pending cascade counts aggregate (1+2).
        assertThat(view.coordinationSignals().declinePendingCascades()).isEqualTo(3);
        // One delegated computation per domain — never re-derived portfolio-side.
        verify(signalService, times(1)).signals(alpha.getId());
        verify(signalService, times(1)).signals(beta.getId());
    }

    @Test
    void demoDomainsAreExcludedFromAProductionPosturePortfolio() {
        when(agentProvider.getIfAvailable()).thenReturn(null);

        PortfolioView view = service(false).portfolio();

        assertThat(view.subsidiaries())
                .extracting(PortfolioView.SubsidiaryCard::name)
                .containsExactlyInAnyOrder("Alpha", "Beta");
        assertThat(view.computedFrom().demoDomainsIncluded()).isFalse();
    }

    @Test
    void absentAgentRegistrySerializesAvailableFalseWithNullCounts_neverZero() throws Exception {
        when(agentProvider.getIfAvailable()).thenReturn(null);

        PortfolioView view = service(false).portfolio();

        assertThat(view.coordinationSignals().agentsAvailable()).isFalse();
        assertThat(view.coordinationSignals().agentsInIntervention()).isNull();
        PortfolioView.AgentStats absent = view.subsidiaries().get(0).agents();
        assertThat(absent.available()).isFalse();
        assertThat(absent.total()).isNull();
        assertThat(absent.interventionRequired()).isNull();

        // Wire-shape pin: agentsAvailable:false vs 0 is structural, not a
        // rendering convention — null counts serialize as null, never 0.
        JsonNode json = new ObjectMapper().valueToTree(absent);
        assertThat(json.get("available").asBoolean()).isFalse();
        assertThat(json.get("total").isNull()).isTrue();
        assertThat(json.get("interventionRequired").isNull()).isTrue();
    }

    @Test
    void presentAgentRegistryWithAnEmptyFleetReportsRealZeros() {
        AgentQueryService agents = mock(AgentQueryService.class);
        when(agentProvider.getIfAvailable()).thenReturn(agents);
        when(agents.signals(any())).thenReturn(new AgentSignals(0, 0, 0, 0, 0,
                new AgentSignals.Interventions(0, null), List.of(), List.of(),
                new AgentSignals.Liveness(0, 0, 0), List.of(), List.of(),
                new AgentSignals.GateCoverage(0, 0, 0)));

        PortfolioView view = service(false).portfolio();

        PortfolioView.AgentStats stats = view.subsidiaries().get(0).agents();
        assertThat(stats.available()).isTrue();
        assertThat(stats.total()).isZero();                       // a REAL zero
        assertThat(view.coordinationSignals().agentsAvailable()).isTrue();
        assertThat(view.coordinationSignals().agentsInIntervention()).isZero();
    }

    @Test
    void unphasedDomainsAreCountedSeparately_neverFoldedIntoStalls() {
        when(agentProvider.getIfAvailable()).thenReturn(null);

        PortfolioView view = service(false).portfolio();

        assertThat(view.unphased()).isEqualTo(2);                 // both have zero rows
        assertThat(view.coordinationSignals().lifecycleStallCount()).isZero();
        assertThat(view.subsidiaries())
                .allSatisfy(card -> {
                    assertThat(card.phaseStatesUninitialized()).isTrue();
                    assertThat(card.lifecyclePhase()).isNull();
                    assertThat(card.lifecycleStalled()).isFalse();
                    // aiReadiness omitted, not invented (spec §15 #15).
                    assertThat(card.aiReadiness()).isNull();
                });
        assertThat(view.computedFrom().aiReadiness()).contains("omitted");
    }
}
