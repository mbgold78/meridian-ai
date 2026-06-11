package com.meridian.substrate.app.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.AgentComplianceReview;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.domain.enums.AgentExecutionLocus;
import com.meridian.substrate.common.domain.enums.AgentGateRuleType;
import com.meridian.substrate.common.domain.enums.AgentInterventionTrigger;
import com.meridian.substrate.common.domain.enums.AgentReviewDecision;
import com.meridian.substrate.common.domain.enums.AgentReviewKind;
import com.meridian.substrate.common.domain.enums.AgentSupervisoryModel;
import com.meridian.substrate.common.repo.AgentComplianceReviewRepository;
import com.meridian.substrate.common.repo.AgentCriteriaGateRepository;
import com.meridian.substrate.common.repo.AgentInterventionRepository;
import com.meridian.substrate.common.repo.AgentMetricSampleRepository;
import com.meridian.substrate.common.repo.AgentNotificationRepository;
import com.meridian.substrate.common.repo.AgentOversightLinkRepository;
import com.meridian.substrate.common.repo.AgentRateCounterRepository;
import com.meridian.substrate.common.repo.AgentRegistrationRepository;
import com.meridian.substrate.common.repo.AgentSightingRepository;
import com.meridian.substrate.common.repo.AgentStatusEventRepository;
import com.meridian.substrate.common.repo.AgentThrottleBudgetRepository;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.engine.agent.AgentActor;
import com.meridian.substrate.engine.agent.AgentGovernanceChainMirror;
import com.meridian.substrate.engine.agent.AgentInterventionService;
import com.meridian.substrate.engine.agent.AgentLifecycleService;
import com.meridian.substrate.engine.agent.AgentLivenessService;
import com.meridian.substrate.engine.agent.AgentMetricService;
import com.meridian.substrate.engine.agent.AgentNotificationService;
import com.meridian.substrate.engine.agent.AgentProperties;
import com.meridian.substrate.engine.agent.AgentPromotionService;
import com.meridian.substrate.engine.agent.AgentQueryService;
import com.meridian.substrate.engine.agent.AgentRegistryLookup;
import com.meridian.substrate.engine.contract.gateway.AgentRegistryGate;
import com.meridian.substrate.engine.decision.log.DecisionLogService;
import com.meridian.substrate.engine.observability.EngineMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DemoPack agent seed (engine-spec agents.md §15; ADR-003 D18): replays all
 * ~70 Beacon fixture agents from {@code classpath:demopack/beacon-agents.json}
 * by DRIVING the real lifecycle services — deploy → (review + promote) →
 * (pause | intervention-open), plus fixture {@code adherence} as an
 * {@code adherence} metric sample and fixture {@code recentActivity} as an
 * ATTESTATION-channel sighting detail — with a stepping fixed clock per
 * action, <b>never row inserts</b>, so the engine computes what the fixture
 * stamped and the seed doubles as a standing state-machine integration test
 * (cascade D16 idiom).
 *
 * <p>{@code beacon-agents.json} is generated VERBATIM from the homepoc
 * fixture truth ({@code meridian-homepoc/frontend/src/demo/beacon/
 * subsidiaries/*.ts}, 9 files, 22 subsidiaries, 70 agents; copied 2026-06-11
 * via {@code frontend/scripts/extract-beacon-agents.ts}; {@code agents.ts}
 * does NOT exist — known doc drift, corrected in ADR-003).
 *
 * <p><b>A Beacon subsidiary IS a Domain</b> (cascade D13): each subsidiary
 * gets its own {@code isDemo=true} Domain row, named exactly the fixture
 * subsidiary name, with metadata {@code {beaconSubsidiary:true, beaconSlug}}.
 * {@code SeedOrchestrator}'s existing-DB path SKIPS these satellite domains —
 * the other seeders' samples belong to the primary demo domain only. The
 * OperationsTab swap resolves the per-subsidiary domain by
 * {@code metadata.beaconSlug} (name fallback).
 *
 * <p><b>Pinned canonical vector — Curasync (§15, all four wire statuses):</b>
 * {@code a-pre-visit}/{@code a-eligibility} deploy(SHADOW)→promote = live;
 * {@code a-coding} deploy only = shadow; {@code a-noshow} deploy→promote→
 * intervention-open (STILL LIVE underneath — two-dimensional model) =
 * intervention-required; {@code a-msg-router} deploy→promote→pause (fixture
 * reason verbatim) = paused. Promotion seeding records an APPROVED PROMOTION
 * review (reviewer = oversight position 1, rationale "seed: fixture agent
 * operating live") before each promote — the M4 gate invariant stays clean.
 *
 * <p><b>Seed-only fiction, flagged (trap #9 — demo data never ships):</b>
 * fixture agents lacking provenance (the majority) get synthesized
 * provenance with a {@code SEED-FICTION:} notes prefix; promote/pause/
 * intervention dates absent from fixtures are deterministic constants;
 * verb actors and attestation identities are the oversight chain's position
 * 1 label; unpolished agents get one UNSTRUCTURED criteria gate from their
 * role text. Only the two polished Curasync mandates compile to STRUCTURED
 * TinyDsl gates (§15): a-pre-visit {@code prefill_accuracy >= 0.90},
 * a-msg-router {@code routing_accuracy >= 0.95} + UNSTRUCTURED "zero PHI
 * leakage" (human-judgment criteria are legitimate — flagged not faked).
 * Fixture {@code capabilityId} slugs are NOT seeded ({@code capability_id}
 * is a UUID soft ref and no Beacon capability rows exist) — a named gap, not
 * an invention.
 *
 * <p>Mechanics: the seeder hand-constructs the engine services over the
 * shared repository beans with the stepping clock (fixture seam S15) — the
 * cascade-seeder pattern; unproxied {@code @Transactional}/{@code @Governed}
 * annotations mean the verbs join the orchestrator's single seed transaction
 * and skip the gateway (demo seed is not a governed operator act). Idempotent
 * re-entry is per-agent-absent, keyed {@code (domain, agent_key)}; satellite
 * domains are per-name-absent. {@code substrate.demo.seed=false} ⇒ this never
 * runs ⇒ zero agents (the 6a acceptance's empty-fleet check).
 */
@Component
public class AgentDemoSeeder implements Seeder {

    private static final Logger log = LoggerFactory.getLogger(AgentDemoSeeder.class);

    static final String BEACON_AGENTS_RESOURCE = "demopack/beacon-agents.json";

    /** Fixture "today" (DEMO_CURRENT_DATE 2026-04-26) — attestation sightings land here. */
    static final Instant DEMO_NOW = Instant.parse("2026-04-26T00:00:00Z");
    /** Adherence samples: the most recent closed monthly period before DEMO_NOW. */
    static final String ADHERENCE_PERIOD = "2026-04";
    static final Instant METRIC_REPORTED_AT = Instant.parse("2026-04-25T00:00:00Z");
    /** SEED-FICTION deterministic dates for facts the fixtures do not carry. */
    static final Instant FICTION_DEPLOYED_AT = Instant.parse("2026-01-12T00:00:00Z");
    static final Instant FICTION_PAUSED_AT = Instant.parse("2026-04-10T00:00:00Z");
    static final Instant INTERVENTION_OPENED_AT = Instant.parse("2026-04-20T00:00:00Z");
    static final Duration PROMOTE_AFTER_DEPLOY = Duration.ofDays(14);

    private static final Pattern PAUSED_DATE = Pattern.compile("Paused (\\d{4}-\\d{2}-\\d{2})");
    private static final String SEED_FICTION_NOTE =
            "SEED-FICTION: provenance synthesized at seed time — the homepoc fixture carries no"
                    + " provenance for this agent (ADR-003 D18; cascade D16 precedent; port-map"
                    + " trap #9: demo-only, never ships in a customer build).";

    private final DomainRepository domainRepository;
    private final AgentRegistrationRepository registrationRepository;
    private final ObjectMapper objectMapper;

    private final SteppingClock clock = new SteppingClock();
    private final AgentLifecycleService lifecycle;
    private final AgentPromotionService promotion;
    private final AgentInterventionService interventions;
    private final AgentMetricService metrics;
    private final AgentLivenessService liveness;

    public AgentDemoSeeder(DomainRepository domainRepository,
                           AgentRegistrationRepository registrationRepository,
                           AgentOversightLinkRepository oversightLinkRepository,
                           AgentStatusEventRepository eventRepository,
                           AgentCriteriaGateRepository criteriaGateRepository,
                           AgentInterventionRepository interventionRepository,
                           AgentComplianceReviewRepository reviewRepository,
                           AgentMetricSampleRepository metricSampleRepository,
                           AgentSightingRepository sightingRepository,
                           AgentNotificationRepository notificationRepository,
                           AgentThrottleBudgetRepository throttleBudgetRepository,
                           AgentRateCounterRepository rateCounterRepository,
                           AgentProperties agentProperties,
                           AgentRegistryLookup registryLookup,
                           AgentRegistryGate registryGate,
                           DecisionLogService decisionLogService,
                           PlatformTransactionManager transactionManager,
                           ObjectMapper objectMapper) {
        this.domainRepository = domainRepository;
        this.registrationRepository = registrationRepository;
        this.objectMapper = objectMapper;

        AgentNotificationService notificationService =
                new AgentNotificationService(notificationRepository);
        this.liveness = new AgentLivenessService(registrationRepository, sightingRepository,
                agentProperties, clock);
        AgentQueryService queryService = new AgentQueryService(registrationRepository,
                oversightLinkRepository, criteriaGateRepository, eventRepository,
                sightingRepository, metricSampleRepository, interventionRepository,
                throttleBudgetRepository, reviewRepository, liveness,
                rateCounterRepository, registryGate, clock);
        // The §11 chain mirror is part of the REAL verb path the seed drives
        // (cascade D16: the seed is a standing integration test) — satellite
        // domains get DEPLOYED/PROMOTED anchors; anchors append after the
        // seed transaction commits (DecisionLogService REQUIRES_NEW posture).
        AgentGovernanceChainMirror chainMirror = new AgentGovernanceChainMirror(
                decisionLogService, eventRepository, objectMapper, transactionManager);
        // EngineMetrics.noop(): demo fixture seeding deliberately stays OUT of
        // the substrate_agent_* ops metrics (Stage 3 observability).
        this.lifecycle = new AgentLifecycleService(registrationRepository,
                oversightLinkRepository, eventRepository, criteriaGateRepository,
                throttleBudgetRepository, notificationService, queryService, liveness,
                chainMirror, registryLookup, clock, objectMapper, EngineMetrics.noop());
        this.promotion = new AgentPromotionService(registrationRepository, eventRepository,
                reviewRepository, sightingRepository, queryService, chainMirror,
                registryLookup, clock, objectMapper, EngineMetrics.noop());
        this.interventions = new AgentInterventionService(registrationRepository,
                interventionRepository, eventRepository, oversightLinkRepository,
                notificationService, queryService, registryLookup, clock, objectMapper,
                EngineMetrics.noop());
        this.metrics = new AgentMetricService(registrationRepository, metricSampleRepository,
                liveness, clock);
    }

    @Override
    public void seedBulk(SeedContext ctx) {
        seedBeacon();
    }

    /**
     * Existing-DB idempotent path. The orchestrator passes each non-satellite
     * demo domain; the beacon seed itself is domain-creating and per-agent
     * idempotent, so one invocation per primary demo domain re-converges the
     * full fleet without a wipe.
     */
    @Override
    public void seedSamplesForDomain(SeedContext ctx, UUID domainId) {
        seedBeacon();
    }

    @Override
    public List<Class<? extends Seeder>> dependsOn() {
        // DomainSeeder first so the PRIMARY demo domain is created before the
        // Beacon satellite domains — creation order is the workspace-pick
        // contract (DomainService.getAllDomains sorts by createdAt).
        return List.of(DomainSeeder.class);
    }

    // ----------------------------------------------------------------- seed

    private void seedBeacon() {
        JsonNode pack = loadPack();
        Map<String, Domain> domainsByName = new HashMap<>();
        for (Domain d : domainRepository.findAll()) {
            domainsByName.put(d.getName(), d);
        }
        int domainsCreated = 0;
        int agentsSeeded = 0;
        int agentsPresent = 0;
        for (JsonNode sub : pack.get("subsidiaries")) {
            String name = sub.get("name").asText();
            Domain domain = domainsByName.get(name);
            if (domain == null) {
                domain = createSatelliteDomain(sub);
                domainsByName.put(name, domain);
                domainsCreated++;
            }
            for (JsonNode agent : sub.get("agents")) {
                String agentKey = agent.get("id").asText();
                if (registrationRepository.findByDomainIdAndAgentKey(domain.getId(), agentKey)
                        .isPresent()) {
                    agentsPresent++;
                    continue;
                }
                seedAgent(domain, sub, agent);
                agentsSeeded++;
            }
        }
        log.info("[seed] AgentDemoSeeder: {} satellite domains created, {} agents seeded via the"
                        + " lifecycle verb path, {} already present (idempotent re-entry)",
                domainsCreated, agentsSeeded, agentsPresent);
    }

    private Domain createSatelliteDomain(JsonNode sub) {
        Domain domain = new Domain();
        domain.setName(sub.get("name").asText());
        domain.setDescription(sub.path("description").asText(null));
        domain.setDemo(true);
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("beaconSubsidiary", true);
        metadata.put("beaconSlug", sub.get("slug").asText());
        domain.setMetadata(metadata);
        domain.setCreatedBy("seed");
        return domainRepository.save(domain);
    }

    /** One agent's §15 action stream — every fact flows through a real verb. */
    private void seedAgent(Domain domain, JsonNode sub, JsonNode agent) {
        String slug = sub.get("slug").asText();
        String agentKey = agent.get("id").asText();
        String status = agent.get("status").asText();
        String recentActivity = agent.get("recentActivity").asText();
        String keyBase = "demo:agent:" + slug + ":" + agentKey;

        // ---- provenance: fixture verbatim where present, SEED-FICTION otherwise
        boolean fiction = false;
        String deployedBy = text(agent, "deployedBy");
        if (deployedBy == null) {
            deployedBy = sub.get("name").asText() + " operations team";
            fiction = true;
        }
        Instant deployedAt;
        String fixtureDeployedAt = text(agent, "deployedAt");
        if (fixtureDeployedAt != null) {
            deployedAt = Instant.parse(fixtureDeployedAt + "T00:00:00Z");
        } else {
            deployedAt = FICTION_DEPLOYED_AT;
            fiction = true;
        }
        String deploymentCriteria = text(agent, "deploymentCriteria");
        if (deploymentCriteria == null) {
            deploymentCriteria = agent.get("role").asText();
            fiction = true;
        }
        List<AgentLifecycleService.OversightEntry> chain = new ArrayList<>();
        if (agent.hasNonNull("oversightChain") && agent.get("oversightChain").size() > 0) {
            for (JsonNode label : agent.get("oversightChain")) {
                chain.add(new AgentLifecycleService.OversightEntry(label.asText(), null));
            }
        } else {
            chain.add(new AgentLifecycleService.OversightEntry(
                    sub.get("name").asText() + " COO", null));
            fiction = true;
        }
        String overseer = chain.get(0).label();

        // ---- M1 deploy (born SHADOW; the §15 streams promote the live ones)
        clock.set(deployedAt);
        AgentLifecycleService.DeployOutcome deployed = lifecycle.deploy(
                new AgentLifecycleService.DeployCommand(
                        keyBase + ":deploy",
                        AgentActor.human(null, deployedBy),
                        domain.getId(),
                        agentKey,
                        agent.get("name").asText(),
                        agent.get("role").asText(),
                        AgentExecutionLocus.CUSTOMER_SYSTEM,
                        null,                       // capabilityId: UUID soft ref; fixture slugs are not seedable (named gap)
                        deployedBy,
                        null,
                        deploymentCriteria,
                        criteriaGates(agentKey, deploymentCriteria, fiction),
                        chain,
                        AgentSupervisoryModel.HUMAN_IN_LOOP,
                        null, null, null, null, null, null, null,
                        fiction ? SEED_FICTION_NOTE : null,
                        null,                       // readOnly → P11 default true
                        null, null, null, null, null));
        UUID regId = deployed.detail().registrationId();

        // ---- promote (live | paused | intervention-required were operating)
        Instant pausedAt = pausedAt(status, recentActivity);
        if (!"shadow".equals(status)) {
            clock.set(deployedAt.plus(Duration.ofDays(7)));
            AgentComplianceReview review = promotion.recordReview(
                    regId, AgentReviewKind.PROMOTION, overseer);
            clock.set(deployedAt.plus(Duration.ofDays(10)));
            promotion.decideReview(review.getId(), AgentReviewDecision.APPROVED, overseer,
                    "seed: fixture agent operating live");
            clock.set(deployedAt.plus(PROMOTE_AFTER_DEPLOY));
            promotion.promote(regId, keyBase + ":promote", AgentActor.human(null, overseer));
        }

        // ---- fixture adherence → adherence metric sample (the engine then
        //      computes what the fixture stamped; null never fabricated)
        clock.set(pausedAt != null ? pausedAt.minus(Duration.ofHours(2)) : METRIC_REPORTED_AT);
        metrics.report(regId, AgentMetricService.METRIC_ADHERENCE,
                agent.get("adherence").asDouble(), ADHERENCE_PERIOD, null);

        // ---- fixture recentActivity → ATTESTATION sighting detail (sighting-
        //      first §7.3 derivation returns the fixture string verbatim).
        //      Paused agents attest BEFORE the pause — no after-pause activity.
        clock.set(pausedAt != null ? pausedAt.minus(Duration.ofHours(1)) : DEMO_NOW);
        liveness.recordAttestation(regId, overseer, recentActivity);

        // ---- terminal overlay verbs
        if ("paused".equals(status)) {
            clock.set(pausedAt);
            lifecycle.pause(regId, keyBase + ":pause", AgentActor.human(null, overseer),
                    recentActivity);
        } else if ("intervention-required".equals(status)) {
            clock.set(INTERVENTION_OPENED_AT);
            interventions.open(regId, keyBase + ":intervention",
                    AgentActor.human(null, overseer), recentActivity, null,
                    AgentInterventionTrigger.HUMAN, null);
        }
    }

    /**
     * §15 criteria gates: the two polished Curasync mandates compile to
     * STRUCTURED TinyDsl predicates (contracts §4.4 vocabulary, VERBATIM —
     * no second DSL); every other mandate ships as ONE UNSTRUCTURED
     * human-judgment gate (predicate NULL — flagged, not faked).
     */
    private List<AgentLifecycleService.CriteriaGateCommand> criteriaGates(
            String agentKey, String deploymentCriteria, boolean fiction) {
        ObjectNode predicate;
        switch (agentKey) {
            case "a-pre-visit" -> {
                predicate = objectMapper.createObjectNode();
                predicate.put("metric", "prefill_accuracy");
                predicate.put("gte", 0.90);
                return List.of(new AgentLifecycleService.CriteriaGateCommand(
                        "target 90% pre-fill accuracy", AgentGateRuleType.STRUCTURED,
                        predicate, "MONTHLY"));
            }
            case "a-msg-router" -> {
                predicate = objectMapper.createObjectNode();
                predicate.put("metric", "routing_accuracy");
                predicate.put("gte", 0.95);
                return List.of(
                        new AgentLifecycleService.CriteriaGateCommand(
                                "target 95% routing accuracy", AgentGateRuleType.STRUCTURED,
                                predicate, "MONTHLY"),
                        new AgentLifecycleService.CriteriaGateCommand(
                                "zero PHI leakage to non-clinical staff",
                                AgentGateRuleType.UNSTRUCTURED, null, "QUARTERLY"));
            }
            default -> {
                return List.of(new AgentLifecycleService.CriteriaGateCommand(
                        deploymentCriteria, AgentGateRuleType.UNSTRUCTURED, null, "QUARTERLY"));
            }
        }
    }

    /** Fixture pause dates parse from the narrative ("Paused 2026-04-19 …"); else deterministic fiction. */
    private static Instant pausedAt(String status, String recentActivity) {
        if (!"paused".equals(status)) {
            return null;
        }
        Matcher m = PAUSED_DATE.matcher(recentActivity);
        return m.find() ? Instant.parse(m.group(1) + "T12:00:00Z") : FICTION_PAUSED_AT;
    }

    private JsonNode loadPack() {
        try (InputStream is = new ClassPathResource(BEACON_AGENTS_RESOURCE).getInputStream()) {
            return objectMapper.readTree(is);
        } catch (IOException e) {
            throw new RuntimeException("AgentDemoSeeder failed to load " + BEACON_AGENTS_RESOURCE, e);
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    /**
     * Fixed clock, re-pinned before each seed action (fixture seam S15) so
     * every deployedAt / occurred_at / seen_at lands exactly on its fixture
     * (or deterministic-fiction) date. UTC-pinned; {@code withZone}
     * unsupported by design (cascade seeder precedent).
     */
    private static final class SteppingClock extends Clock {

        private Instant current = Instant.EPOCH;

        void set(Instant instant) {
            this.current = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException(
                    "DemoPack stepping clock is UTC-pinned (fixture seam S15)");
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
