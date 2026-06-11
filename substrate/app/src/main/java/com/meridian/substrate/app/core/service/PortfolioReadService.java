package com.meridian.substrate.app.core.service;

import com.meridian.substrate.app.config.StatsProperties;
import com.meridian.substrate.app.core.service.read.InitiativeView;
import com.meridian.substrate.app.core.service.read.PortfolioView;
import com.meridian.substrate.app.core.service.read.SubsidiaryDetailView;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.domain.Finding;
import com.meridian.substrate.common.domain.PhaseState;
import com.meridian.substrate.common.domain.TransformationPlan;
import com.meridian.substrate.common.domain.enums.CurationStatus;
import com.meridian.substrate.common.domain.enums.LifecyclePhase;
import com.meridian.substrate.common.domain.enums.PhaseStatus;
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
import com.meridian.substrate.engine.contract.read.ContractCardResponse;
import com.meridian.substrate.engine.contract.read.SignalsResponse;
import com.meridian.substrate.engine.contract.registry.ContractReadService;
import com.meridian.substrate.engine.contract.registry.ContractSignalService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * D5 — computed statistics + portfolio composition (engine-spec diagnostic.md
 * §12; ADR-004 D13). Severs fixture stat seam S6: every number the Beacon
 * dashboards previously stamped ({@code lifecycleStallCount},
 * {@code coordinationPatternsThisWeek}, {@code pendingFederationInvitations},
 * {@code progressPct}, {@code surfacedAt}) is engine-computed at READ TIME
 * from existing tables. No stats tables, no LLM anywhere in the path (P7);
 * computed per request — REVISIT at &gt;50 domains or aggressive polling.
 *
 * <p><b>Delegation, never re-derivation:</b> the §12.2/§12.3 contract numbers
 * come from the SAME {@link ContractSignalService} bean that serves
 * {@code GET /contracts/signals} — one computation, two surfaces, zero drift.
 * EXTENSION POINT(diagnostic-10): a richer coordination-pattern union
 * (monitor signals, sibling-decline advisories) is a contracts §11.2
 * SignalService-spec amendment, never a portfolio-side fork. Cascade
 * decline-pending counts come from {@link CascadeQueryService} (D1), agent
 * stats from the D3 registry read service when its bean exists.
 *
 * <p><b>Demo isolation</b> (§12): the portfolio is the engagement's non-demo
 * domain set. A demo-seeded boot ({@code substrate.demo.seed=true}) IS the
 * demo engagement, so its {@code isDemo} domains form the portfolio there —
 * a production build (seed=false default) can never surface demo rows;
 * {@code computedFrom.demoDomainsIncluded} states which posture produced the
 * numbers.
 */
@Service
public class PortfolioReadService {

    /** §12.1: PLUG_IN is excluded — continuous, never exits, never "current". */
    static final List<LifecyclePhase> SEQUENCED_PHASES = List.of(
            LifecyclePhase.DESIGN, LifecyclePhase.MAP, LifecyclePhase.PLAN,
            LifecyclePhase.EXECUTE, LifecyclePhase.MONITOR, LifecyclePhase.AUTOMATE);

    /** Contract-type allowlist value the §12.7 opportunity rail composes on. */
    static final String CROSS_PORTFOLIO_OPPORTUNITY = "cross-portfolio-opportunity";

    /** Wire key of the D1 decline-pending attention signal in {@code CascadeStats.bySignal}. */
    static final String DECLINE_PENDING_SIGNAL = "decline-pending";

    /** The §15 row-15 omission note, verbatim in every computedFrom block. */
    static final String AI_READINESS_OMITTED =
            "omitted — no computation basis until the D3 readiness lens lands"
                    + " (engine-spec diagnostic.md §15 #15; flag-not-invent)";

    private final DomainRepository domainRepository;
    private final PhaseStateRepository phaseStateRepository;
    private final FindingRepository findingRepository;
    private final CapabilityRepository capabilityRepository;
    private final ProcessRepository processRepository;
    private final SystemRepository systemRepository;
    private final TransformationPlanRepository planRepository;
    private final TransformationInitiativeRepository initiativeRepository;
    private final ContractSignalService contractSignalService;
    private final ContractReadService contractReadService;
    private final CascadeQueryService cascadeQueryService;
    private final ObjectProvider<AgentQueryService> agentQueryService;
    private final StatsProperties statsProperties;
    private final Clock clock;
    private final String companyName;
    private final boolean demoDomainsIncluded;

    public PortfolioReadService(DomainRepository domainRepository,
                                PhaseStateRepository phaseStateRepository,
                                FindingRepository findingRepository,
                                CapabilityRepository capabilityRepository,
                                ProcessRepository processRepository,
                                SystemRepository systemRepository,
                                TransformationPlanRepository planRepository,
                                TransformationInitiativeRepository initiativeRepository,
                                ContractSignalService contractSignalService,
                                ContractReadService contractReadService,
                                CascadeQueryService cascadeQueryService,
                                ObjectProvider<AgentQueryService> agentQueryService,
                                StatsProperties statsProperties,
                                Clock clock,
                                // S10: engagement config identity — never baked demo identity.
                                @Value("${substrate.engagement.display-name:Substrate Engagement}")
                                String companyName,
                                @Value("${substrate.demo.seed:false}")
                                boolean demoDomainsIncluded) {
        this.domainRepository = domainRepository;
        this.phaseStateRepository = phaseStateRepository;
        this.findingRepository = findingRepository;
        this.capabilityRepository = capabilityRepository;
        this.processRepository = processRepository;
        this.systemRepository = systemRepository;
        this.planRepository = planRepository;
        this.initiativeRepository = initiativeRepository;
        this.contractSignalService = contractSignalService;
        this.contractReadService = contractReadService;
        this.cascadeQueryService = cascadeQueryService;
        this.agentQueryService = agentQueryService;
        this.statsProperties = statsProperties;
        this.clock = clock;
        this.companyName = companyName;
        this.demoDomainsIncluded = demoDomainsIncluded;
    }

    // ------------------------------------------------------------ §12.7 reads

    /** {@code GET /api/v1/portfolio} — the Operating-Partner composition. */
    @Transactional(readOnly = true)
    public PortfolioView portfolio() {
        List<Domain> domains = portfolioDomains();
        Instant now = clock.instant();
        Duration threshold = Duration.ofDays(statsProperties.getStallThresholdDays());
        AgentQueryService agents = agentQueryService.getIfAvailable();

        long stallCount = 0;
        long unphased = 0;
        long declinePending = 0;
        long patterns = 0;
        long contractEvents = 0;
        long cascadeActions = 0;
        long federationTotal = 0;
        Integer interventionTotal = agents != null ? 0 : null;
        Map<String, Long> needsInputByKind = new LinkedHashMap<>();
        List<PortfolioView.SubsidiaryCard> cards = new ArrayList<>();
        List<ContractCardResponse> opportunities = new ArrayList<>();

        for (Domain d : domains) {
            List<PhaseState> rows = phaseStateRepository.findByDomainId(d.getId());
            Optional<PhaseState> current = currentPhase(rows);
            boolean stalled = isStalled(rows, now, threshold);
            if (rows.isEmpty()) {
                unphased++;                       // SEPARATE count — never a stall (§12.1)
            }
            if (stalled) {
                stallCount++;
            }

            SignalsResponse signals = contractSignalService.signals(d.getId());
            patterns += signals.coordinationPatternsThisWeek();
            if (signals.coordinationBreakdown() != null) {
                contractEvents += signals.coordinationBreakdown().contractEvents();
                cascadeActions += signals.coordinationBreakdown().cascadeActions();
            }
            federationTotal += signals.pendingFederationInvitations();
            for (SignalsResponse.NeedsInputItem item : signals.needsInput()) {
                needsInputByKind.merge(item.kind(), 1L, Long::sum);
            }

            declinePending += cascadeQueryService.stats(d.getId())
                    .bySignal().getOrDefault(DECLINE_PENDING_SIGNAL, 0L);

            PortfolioView.AgentStats agentStats = agentStats(agents, d.getId());
            if (interventionTotal != null && agentStats.interventionRequired() != null) {
                interventionTotal += agentStats.interventionRequired();
            }

            cards.add(new PortfolioView.SubsidiaryCard(
                    d.getId(), d.getName(), wirePhase(current), rows.isEmpty(), stalled,
                    findingRollup(d.getId()), agentStats,
                    signals.pendingFederationInvitations(),
                    null /* aiReadiness — EXTENSION POINT(diagnostic-15), omitted not invented */));

            opportunities.addAll(
                    contractReadService.cards(d.getId(), null, CROSS_PORTFOLIO_OPPORTUNITY));
        }

        PortfolioView.CoordinationSignals strip = new PortfolioView.CoordinationSignals(
                stallCount,
                patterns,
                new SignalsResponse.CoordinationBreakdown(contractEvents, cascadeActions),
                declinePending,
                agents != null,
                interventionTotal,
                needsInputByKind,
                federationTotal);

        return new PortfolioView(companyName, cards, strip, opportunities, unphased,
                computedFrom(now, domains.size(), agents != null));
    }

    /** {@code GET /api/v1/portfolio/subsidiaries/{domainId}} — the COO composition. */
    @Transactional(readOnly = true)
    public SubsidiaryDetailView subsidiary(UUID domainId) {
        Domain domain = domainRepository.findById(domainId)
                .filter(d -> !d.isDemo() || demoDomainsIncluded)   // demo isolation (§12)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Portfolio subsidiary not found: " + domainId));

        Instant now = clock.instant();
        Duration threshold = Duration.ofDays(statsProperties.getStallThresholdDays());
        AgentQueryService agents = agentQueryService.getIfAvailable();

        List<PhaseState> rows = phaseStateRepository.findByDomainId(domainId);
        SignalsResponse signals = contractSignalService.signals(domainId);

        List<SubsidiaryDetailView.CapabilityRow> capabilities = capabilityRepository
                .findByDomainId(domainId).stream()
                .sorted(Comparator.comparing(c -> c.getName() == null ? "" : c.getName()))
                .map(c -> new SubsidiaryDetailView.CapabilityRow(c.getId(),
                        c.getParentCapabilityId(), c.getName(), c.getDisplayName(),
                        c.getLevel(), name(c.getStatus())))
                .toList();
        List<SubsidiaryDetailView.ProcessRow> processes = processRepository
                .findByDomainId(domainId).stream()
                .map(p -> new SubsidiaryDetailView.ProcessRow(p.getId(), p.getName(),
                        name(p.getClassification())))
                .toList();
        List<SubsidiaryDetailView.SystemRow> systems = systemRepository
                .findByDomainId(domainId).stream()
                .map(s -> new SubsidiaryDetailView.SystemRow(s.getId(), s.getName(),
                        s.getVendor(), s.getSystemType()))
                .toList();

        List<Finding> allFindings = findingRepository.findByDomainId(domainId);
        List<SubsidiaryDetailView.FindingRow> findingRows = allFindings.stream()
                .filter(PortfolioReadService::clientSurfaced)
                .map(f -> new SubsidiaryDetailView.FindingRow(f.getId(), name(f.getSeverity()),
                        name(f.getFindingType()), f.getTitle(), f.getDescription(),
                        Boolean.TRUE.equals(f.getAiGenerated()), name(f.getCurationStatus()),
                        surfacedAt(f)))
                .toList();

        List<TransformationPlan> plans = planRepository.findByDomainIdOrderByCreatedAtAsc(domainId);
        List<InitiativeView> initiatives = plans.isEmpty() ? List.of()
                : initiativeRepository.findByPlanIdInOrderByCreatedAtAsc(
                        plans.stream().map(TransformationPlan::getId).toList())
                        .stream().map(InitiativeView::from).toList();

        SubsidiaryDetailView.AttentionBlock attention = new SubsidiaryDetailView.AttentionBlock(
                cascadeQueryService.attention(domainId, null),
                agents != null ? agents.attention(domainId) : null);

        return new SubsidiaryDetailView(
                domain.getId(), domain.getName(), domain.getDescription(),
                wirePhase(currentPhase(rows)), rows.isEmpty(), isStalled(rows, now, threshold),
                null /* aiReadiness — EXTENSION POINT(diagnostic-15) */,
                capabilities, processes, systems,
                findingRows, findingRollup(domainId),
                agentStats(agents, domainId),
                agents != null ? agents.fleet(domainId, null, false) : null,
                initiatives,
                signals.pendingFederationInvitations(),
                attention,
                computedFrom(now, 1, agents != null));
    }

    // -------------------------------------------------- §12.1 stall formula

    /**
     * §12.1 exact: the PhaseState row with the highest {@link #SEQUENCED_PHASES}
     * index whose status ∈ {IN_PROGRESS, BLOCKED}; empty if none. PLUG_IN is
     * never current (continuous, never exits).
     */
    public static Optional<PhaseState> currentPhase(List<PhaseState> rows) {
        PhaseState best = null;
        int bestIdx = -1;
        for (PhaseState row : rows) {
            int idx = SEQUENCED_PHASES.indexOf(row.getPhase());
            if (idx < 0) {
                continue;
            }
            if (row.getStatus() != PhaseStatus.IN_PROGRESS && row.getStatus() != PhaseStatus.BLOCKED) {
                continue;
            }
            if (idx > bestIdx) {
                bestIdx = idx;
                best = row;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * §12.1 exact: {@code stalled(d) := currentPhase(d) defined AND (status =
     * BLOCKED OR (status = IN_PROGRESS AND now − max(enteredAt, updatedAt) >
     * threshold))}. Checkpoint/blocker edits count as movement via
     * {@code updatedAt}; COMPLETE-only domains have no current phase and are
     * never stalled; zero-row domains are the separate {@code unphased} count.
     */
    public static boolean isStalled(List<PhaseState> rows, Instant now, Duration threshold) {
        return currentPhase(rows).map(current -> {
            if (current.getStatus() == PhaseStatus.BLOCKED) {
                return true;
            }
            Instant lastMovement = max(current.getEnteredAt(), current.getUpdatedAt());
            return lastMovement != null && now.isAfter(lastMovement.plus(threshold));
        }).orElse(false);
    }

    private static Instant max(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }

    // ----------------------------------------------- §12.4 surfacedAt rule

    /**
     * §12.4 exact projection: operator/fixture rows ({@code curationStatus}
     * NULL) surface at {@code createdAt}; ACCEPTED AI rows surface at the
     * ACCEPT-event instant ({@code curatedAt} — the client-visibility moment
     * under the D10 gate); everything else is not client-surfaced.
     * EXTENSION POINT(diagnostic-4): becomes {@code createdAt} everywhere if
     * Mike overrules the gate — a projection change only.
     */
    public static Instant surfacedAt(Finding f) {
        if (f.getCurationStatus() == null) {
            return f.getCreatedAt();
        }
        if (f.getCurationStatus() == CurationStatus.ACCEPTED) {
            return f.getCuratedAt();
        }
        return null;
    }

    /** D10 client-surface predicate: NULL (operator/fixture) and ACCEPTED rows only. */
    public static boolean clientSurfaced(Finding f) {
        return f.getCurationStatus() == null || f.getCurationStatus() == CurationStatus.ACCEPTED;
    }

    /** §12.6 operator default filter: {@code NOT IN (REJECTED, SUPERSEDED) OR NULL}. */
    public static boolean operatorSurfaced(Finding f) {
        return f.getCurationStatus() != CurationStatus.REJECTED
                && f.getCurationStatus() != CurationStatus.SUPERSEDED;
    }

    // ------------------------------------------------------------- helpers

    /**
     * The portfolio domain set: non-demo domains, plus demo domains ONLY on a
     * demo-seeded boot (demo isolation, §12). Deterministic order: createdAt
     * then id (the workspace-pick convention).
     */
    private List<Domain> portfolioDomains() {
        return domainRepository.findAll().stream()
                .filter(d -> !d.isDemo() || demoDomainsIncluded)
                .sorted(Comparator.comparing(Domain::getCreatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Domain::getId))
                .toList();
    }

    /** Fixture kebab vocabulary at the swap seam (e.g. DESIGN → "design"). */
    private static String wirePhase(Optional<PhaseState> current) {
        return current
                .map(p -> p.getPhase().name().toLowerCase(Locale.ROOT).replace('_', '-'))
                .orElse(null);
    }

    private PortfolioView.AgentStats agentStats(AgentQueryService agents, UUID domainId) {
        if (agents == null) {
            return PortfolioView.AgentStats.absent();   // absent ≠ zero (test plan #14)
        }
        AgentSignals s = agents.signals(domainId);
        return new PortfolioView.AgentStats(true, s.agentsTotal(), s.agentsLive(),
                s.agentsShadow(), s.agentsPaused(), s.agentsInterventionRequired());
    }

    /** §12.6 rollups — both variants, null severity surfaced as UNSPECIFIED. */
    private PortfolioView.FindingRollup findingRollup(UUID domainId) {
        List<Finding> findings = findingRepository.findByDomainId(domainId);
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        Map<String, Long> clientBySeverity = new LinkedHashMap<>();
        long aiGenerated = 0;
        long operatorAuthored = 0;
        for (Finding f : findings) {
            if (!operatorSurfaced(f)) {
                continue;                               // a rejected AI claim never inflates a dashboard
            }
            String severity = f.getSeverity() == null ? "UNSPECIFIED" : f.getSeverity().name();
            bySeverity.merge(severity, 1L, Long::sum);
            if (Boolean.TRUE.equals(f.getAiGenerated())) {
                aiGenerated++;
            } else {
                operatorAuthored++;
            }
            if (clientSurfaced(f)) {
                clientBySeverity.merge(severity, 1L, Long::sum);
            }
        }
        return new PortfolioView.FindingRollup(bySeverity, aiGenerated, operatorAuthored,
                clientBySeverity);
    }

    private PortfolioView.ComputedFrom computedFrom(Instant now, int domainsConsidered,
                                                    boolean agentRegistryAvailable) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("lifecycleStallCount", "phase_states dwell-time (§12.1; threshold "
                + statsProperties.getStallThresholdDays() + "d)");
        sources.put("coordinationPatternsThisWeek",
                "ContractSignalService §11.2 (delegated — same bean, never re-derived)");
        sources.put("pendingFederationInvitations", "ContractSignalService §11.2 (delegated)");
        sources.put("declinePendingCascades", "CascadeQueryService stats (D1 attention feed)");
        sources.put("agents", agentRegistryAvailable
                ? "agent registry read service (D3)" : "absent — agentsAvailable:false, counts null");
        sources.put("findingCounts", "findings rollup (§12.6; curation-filtered)");
        sources.put("progressPct", "operator-maintained V8 initiatives (§12.5; COMPLETE⇒100 only rule)");
        return new PortfolioView.ComputedFrom(
                now,
                statsProperties.getStallThresholdDays(),
                (int) ContractSignalService.COORDINATION_WINDOW.toDays(),
                demoDomainsIncluded,
                domainsConsidered,
                agentRegistryAvailable,
                AI_READINESS_OMITTED,
                sources);
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
