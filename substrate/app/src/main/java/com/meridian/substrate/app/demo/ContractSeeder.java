package com.meridian.substrate.app.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.app.config.AppProperties;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractParty;
import com.meridian.substrate.common.domain.enums.ContractPartyType;
import com.meridian.substrate.common.domain.enums.ContractStatus;
import com.meridian.substrate.common.domain.enums.MonitorRuleType;
import com.meridian.substrate.common.domain.enums.PartyResponse;
import com.meridian.substrate.common.repo.ContractPartyRepository;
import com.meridian.substrate.common.repo.ContractRepository;
import com.meridian.substrate.engine.contract.gateway.GovernedActions;
import com.meridian.substrate.engine.contract.registry.ContractActor;
import com.meridian.substrate.engine.contract.registry.ContractRegistryService;
import com.meridian.substrate.engine.contract.registry.ContractRegistryService.CommitmentCommand;
import com.meridian.substrate.engine.contract.registry.ContractRegistryService.CommitmentRef;
import com.meridian.substrate.engine.contract.registry.ContractRegistryService.CreateContractCommand;
import com.meridian.substrate.engine.contract.registry.ContractRegistryService.DeclineTriggerCommand;
import com.meridian.substrate.engine.contract.registry.ContractRegistryService.EntityLinkCommand;
import com.meridian.substrate.engine.contract.registry.ContractRegistryService.GuardCommand;
import com.meridian.substrate.engine.contract.registry.ContractRegistryService.MonitorCommand;
import com.meridian.substrate.engine.contract.registry.ContractRegistryService.PartyCommand;
import com.meridian.substrate.common.domain.enums.GuardEffect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DemoPack contract seed (engine-spec contracts.md §14.2; ADR-002 D18): seeds
 * the two §14.1 fixture vectors PROPOSED into the isDemo domain by DRIVING the
 * real {@link ContractRegistryService} create path — never row inserts — so
 * the seed doubles as a standing test of the §7.2 create semantics.
 *
 * <p><b>Fixture source of truth (binding, §14.1):</b> every string below is
 * copied VERBATIM from
 * {@code C:/Users/mbgol/dev/meridian-homepoc/frontend/src/demo/beacon/contracts.ts}
 * — the FILE, not prose (known narrative-drift trap, port-map §6 caveat +
 * appendix #9). Copied 2026-06-10. Beacon fixture data is fictional —
 * DemoPack-gated only ({@code substrate.demo.seed}), never in a customer
 * engagement build.
 *
 * <p>Seed-only derivations the fixture file does not carry (flagged †):
 * <ul>
 *   <li>† {@code proposedBy}: "Beacon Corporate Operations" /
 *       "Polestar Life Admin (federation host)" — derived from fixture
 *       narrative (the proposing party); the fixture has no proposer field.</li>
 *   <li>† party types: Beacon Corporate Operations = ORG_UNIT (pinned by the
 *       spec §7.3 example); "Sentari, Tellix, Bursar (combined obligation)" =
 *       COMBINED (pinned §14.1); subsidiary parties = DOMAIN (a Beacon
 *       subsidiary IS a Domain — cascade-spec §11.3 posture).</li>
 *   <li>† the MONTHLY cadence on the Curasync monthly-report commitment —
 *       the one commitment the compiled ATTESTATION_CADENCE monitor binds to;
 *       no other commitment cadence is pinned by §14.1, so none is set.</li>
 *   <li>† activate-lever responder identities = the party labels (the
 *       fixture defines no responder names).</li>
 * </ul>
 *
 * <p>Compiled monitors per §14.1 / ADR D10 (verbatim {@code monitorMechanism}
 * prose stored alongside; the structured {@code rule_spec} sibling is what
 * executes): vector 1 → {@code missing-monthly-report} ATTESTATION_CADENCE
 * MONTHLY/grace 7 surfacing to the Operating Partner dashboard; vector 2 →
 * {@code utilization-imbalance} METRIC_THRESHOLD QUARTERLY over
 * {@code utilization_share} with threshold 0.30 requiring BOTH parties
 * (seeded with zero samples ⇒ first 3b run INDETERMINATE). Both contracts
 * compile to ZERO guards (spec §16 — do not let demo pressure invent any) and
 * {@code review_boundary = null} (§6.2 unenforced-when-null).
 *
 * <p>Idempotent on re-boot keyed {@code (domain, contract_key)} (§14.2).
 * {@code substrate.demo.contracts.activate=true} additionally records
 * ACCEPTED responses for all required parties of vector 1, flipping it ACTIVE
 * (monitors ACTIVE, guards live — the 3b demo lever); the lever is itself
 * idempotent (duplicate identical responses are §10.2 no-ops).
 */
@Component
public class ContractSeeder implements Seeder {

    private static final Logger log = LoggerFactory.getLogger(ContractSeeder.class);

    static final String CO_TRIBAL_KEY = "co-tribal-knowledge-depopulation";
    static final String FI_SHARED_KEY = "fi-shared-pre-fill-agent";
    /** §13 row 3c — the SYNTHETIC guard-bearing acceptance contract (lever-gated). */
    static final String GATEWAY_GUARDS_KEY = "demo-gateway-guards";
    /** Seed-only principal the agent-fleet party binds to ({@code mcp_principal_id}). */
    static final String GATEWAY_DEMO_PRINCIPAL = "demo-agent";

    private final ContractRegistryService registryService;
    private final ContractRepository contractRepository;
    private final ContractPartyRepository partyRepository;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public ContractSeeder(ContractRegistryService registryService,
                          ContractRepository contractRepository,
                          ContractPartyRepository partyRepository,
                          AppProperties appProperties,
                          ObjectMapper objectMapper) {
        this.registryService = registryService;
        this.contractRepository = contractRepository;
        this.partyRepository = partyRepository;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void seedBulk(SeedContext ctx) {
        seedFixtures(ctx.getDomainId());
    }

    /** Existing-DB idempotent path; the orchestrator only ever passes isDemo domains. */
    @Override
    public void seedSamplesForDomain(SeedContext ctx, UUID domainId) {
        seedFixtures(domainId);
    }

    @Override
    public List<Class<? extends Seeder>> dependsOn() {
        return List.of(DomainSeeder.class);
    }

    // ------------------------------------------------------------ the fixtures

    private void seedFixtures(UUID domainId) {
        int seeded = 0;
        seeded += seedCoTribal(domainId) ? 1 : 0;
        seeded += seedFiShared(domainId) ? 1 : 0;
        if (appProperties.getDemo().getContracts().isActivate()) {
            activateCoTribal(domainId);
        }
        if (appProperties.getDemo().getContracts().isGuards()) {
            seedGatewayGuards(domainId);
        }
        log.info("[seed] ContractSeeder: {} of 2 fixture contracts seeded via the registry"
                        + " create path [domainId={}, activate={}, guards={}] (0 = already"
                        + " present, idempotent re-entry keyed (domain, contract_key))",
                seeded, domainId, appProperties.getDemo().getContracts().isActivate(),
                appProperties.getDemo().getContracts().isGuards());
    }

    /** §14.1 vector 1 — {@code co-tribal-knowledge-depopulation}, seeded PROPOSED. */
    private boolean seedCoTribal(UUID domainId) {
        if (contractRepository.findByDomainIdAndContractKey(domainId, CO_TRIBAL_KEY).isPresent()) {
            return false;
        }
        Contract c = registryService.create(new CreateContractCommand(
                domainId,
                CO_TRIBAL_KEY,
                "cross-portfolio-opportunity",
                "Tribal-knowledge depopulation across four healthcare/insurance subs",
                "Four subsidiaries have key processes owned by single individuals with no"
                        + " documented succession. Beacon proposes a coordinated"
                        + " knowledge-depopulation initiative across these four subs, sharing"
                        + " methodology and named successor candidates.",
                "Beacon Corporate runs monthly compliance checks against each subsidiary's"
                        + " reported progress. Quarterly reviews validate successor-candidate"
                        + " adequacy.",
                "MCP coordination contract id co-tribal-knowledge-depopulation; substrate"
                        + " auto-flags subsidiary missing-monthly-report after 7 grace days;"
                        + " surface to Operating Partner dashboard.",
                "$1.2M annually across four subsidiaries",
                null,                               // no structured value sibling in the fixture
                null,                               // review_boundary = null (§6.2/§14.2)
                Instant.parse("2026-04-01T00:00:00Z"),
                "Beacon Corporate Operations",      // † derived proposer (see class javadoc)
                List.of(
                        new PartyCommand("Beacon Corporate Operations",
                                ContractPartyType.ORG_UNIT, null, true, List.of(
                                new CommitmentCommand("Provide the knowledge-depopulation"
                                        + " methodology and template", null, null),
                                new CommitmentCommand("Coordinate quarterly review across the"
                                        + " four subsidiaries", null, null),
                                new CommitmentCommand("Surface successor-candidate gaps to"
                                        + " subsidiary COOs within 30 days", null, null))),
                        new PartyCommand("Curasync Clinical Systems",
                                ContractPartyType.DOMAIN, null, true, List.of(
                                new CommitmentCommand("Identify and document the three"
                                        + " highest-risk single-owner processes by Q3", null, null),
                                new CommitmentCommand("Name and onboard at least one successor"
                                        + " candidate per process by Q4", null, null),
                                // † MONTHLY: the commitment the compiled
                                // missing-monthly-report monitor binds to.
                                new CommitmentCommand("Report monthly progress against the"
                                        + " Beacon-provided template", null, "MONTHLY"))),
                        new PartyCommand("Sentari, Tellix, Bursar (combined obligation)",
                                ContractPartyType.COMBINED, null, true, List.of(
                                new CommitmentCommand("Each subsidiary mirrors the Curasync"
                                        + " commitments on their own timeline", null, null),
                                new CommitmentCommand("Each provides quarterly methodology"
                                        + " feedback to Beacon Corporate", null, null)))),
                List.of(
                        // All three predicate-null: pure human-judgment grounds (§14.1).
                        new DeclineTriggerCommand("Subsidiary cannot identify successor"
                                + " candidates due to specialized regulated role (e.g., licensed"
                                + " compliance officer in a state where bench is small)",
                                null, null),
                        new DeclineTriggerCommand("Subsidiary acquires a third party with"
                                + " overlapping process and proposes alternative consolidation",
                                null, null),
                        new DeclineTriggerCommand("Beacon Corporate methodology proves"
                                + " insufficient for subsidiary's domain", null, null)),
                List.of(new MonitorCommand("missing-monthly-report",
                        MonitorRuleType.ATTESTATION_CADENCE, null, "MONTHLY", 7,
                        "OPERATING_PARTNER_DASHBOARD", null,
                        // (partyIndex 1 = Curasync, commitmentIndex 2 = monthly report)
                        List.of(new CommitmentRef(1, 2)), null)),
                List.of(),                          // ZERO guards — spec §16
                List.of(
                        new EntityLinkCommand("SUBSIDIARY_SLUG", null, "curasync-clinical", null),
                        new EntityLinkCommand("SUBSIDIARY_SLUG", null, "sentari-lab", null),
                        new EntityLinkCommand("SUBSIDIARY_SLUG", null, "tellix-sensors", null),
                        new EntityLinkCommand("SUBSIDIARY_SLUG", null, "bursar-campus", null))));
        log.info("[seed]   {} [id={}, status={}]", CO_TRIBAL_KEY, c.getId(), c.getStatus());
        return true;
    }

    /** §14.1 vector 2 — {@code fi-shared-pre-fill-agent}, seeded PROPOSED, NO estimatedValue. */
    private boolean seedFiShared(UUID domainId) {
        if (contractRepository.findByDomainIdAndContractKey(domainId, FI_SHARED_KEY).isPresent()) {
            return false;
        }
        Contract c = registryService.create(new CreateContractCommand(
                domainId,
                FI_SHARED_KEY,
                "federation-invitation",
                "Shared compliance-form pre-fill agent (Polestar invites Curasync)",
                "Polestar has deployed a compliance-form pre-fill agent against"
                        + " HIPAA-attestation forms. Curasync's regulated workflows have similar"
                        + " form-attestation overhead. Polestar invites Curasync to join the"
                        + " federation; the agent expands its scope to include Curasync's form"
                        + " set in exchange for Curasync absorbing 40% of agent maintenance.",
                "Quarterly federated review evaluates utilization per subsidiary and"
                        + " cost-share fairness. Either party may invoke Decline at quarterly"
                        + " review boundaries.",
                "MCP federation contract id fi-shared-pre-fill-agent; substrate auto-flags"
                        + " utilization imbalance > 30% between parties for review.",
                null,                               // NO estimatedValue — null must serialize (§14.1)
                null,
                null,                               // review_boundary = null (§6.2 ALLOW_WITH_FLAG lane)
                Instant.parse("2026-04-15T00:00:00Z"),
                "Polestar Life Admin (federation host)", // † derived proposer
                List.of(
                        new PartyCommand("Polestar Life Admin (federation host)",
                                ContractPartyType.DOMAIN, null, true, List.of(
                                new CommitmentCommand("Maintain agent codebase and accept"
                                        + " Curasync-submitted form additions", null, null),
                                new CommitmentCommand("Federate agent oversight with Curasync's"
                                        + " HIM Director per shared-oversight pattern", null, null),
                                new CommitmentCommand("Provide quarterly utilization reporting"
                                        + " to both parties", null, null))),
                        new PartyCommand("Curasync Clinical Systems",
                                ContractPartyType.DOMAIN, null, true, List.of(
                                new CommitmentCommand("Absorb 40% of agent maintenance cost",
                                        null, null),
                                new CommitmentCommand("Submit Curasync's form-attestation set"
                                        + " within 30 days of acceptance", null, null),
                                new CommitmentCommand("Participate in joint quarterly review"
                                        + " with Polestar", null, null)))),
                List.of(
                        new DeclineTriggerCommand("Curasync HIPAA attestation framework changes"
                                + " incompatibly with shared agent", null, null),
                        // The arming demo (§14.1): machine-detectable within the
                        // v1 DSL — metric leaf, evaluated by the 3b sweep.
                        new DeclineTriggerCommand("Polestar agent reaches accuracy threshold"
                                + " below 90%",
                                objectMapper.createObjectNode()
                                        .put("metric", "accuracy")
                                        .put("lt", 0.90),
                                null),
                        // Predicate-null BY DESIGN: 'sustained over two quarters'
                        // needs temporal semantics the v1 DSL deliberately lacks
                        // (ADR D9 revisit example; spec §2.6/§15 #6) — ships as
                        // human judgment, always invocable.
                        new DeclineTriggerCommand("Cost-share imbalance > 30% sustained over"
                                + " two quarters", null, null)),
                List.of(new MonitorCommand("utilization-imbalance",
                        MonitorRuleType.METRIC_THRESHOLD,
                        objectMapper.createObjectNode()
                                .put("metricKey", "utilization_share")
                                .put("threshold", 0.30),
                        "QUARTERLY", 0, null, null,
                        null,
                        // Requires samples from BOTH parties — zero samples are
                        // seeded, so the first 3b run is INDETERMINATE (§14.1 pin;
                        // ADR D11: never computes on partial data).
                        List.of(0, 1))),
                List.of(),                          // ZERO guards — spec §16
                List.of(
                        new EntityLinkCommand("SUBSIDIARY_SLUG", null, "curasync-clinical", null),
                        new EntityLinkCommand("SUBSIDIARY_SLUG", null, "polestar-life", null))));
        log.info("[seed]   {} [id={}, status={}]", FI_SHARED_KEY, c.getId(), c.getStatus());
        return true;
    }

    /**
     * §13 row 3c / spec §16: the SYNTHETIC guard-bearing contract — both
     * fixture contracts compile to ZERO guards by design, so the gateway
     * acceptance needs a deliberately synthetic vector (never fixture
     * content; lever-gated, never in a customer engagement build). Two DENY
     * guards, both predicate-null:
     * <ul>
     *   <li>{@code agent.promote} — the §13 {@code check_action} → BLOCK
     *       lane (no substrate write maps to it; advisory-only here);</li>
     *   <li>{@code contract.report_metric} — a REAL governed verb on BOTH
     *       transports: REST {@code POST /contracts/{key}/metric-samples}
     *       (shadow ⇒ 200 + WOULD_DENY row; enforce ⇒ 409 CONTRACT_DENIAL)
     *       and the MCP {@code report_metric} tool (enforce ⇒ structured
     *       {@code isError}).</li>
     * </ul>
     * Activated through the real respond path (guards live only while
     * ACTIVE, §2.2). The agent-fleet party is bound to
     * {@value #GATEWAY_DEMO_PRINCIPAL} (seed-only fiction — §5.5 party
     * authority resolution for the MCP lane; no binding API exists
     * pre-Phase-5). Idempotent on re-boot like the fixture vectors.
     */
    private void seedGatewayGuards(UUID domainId) {
        if (contractRepository.findByDomainIdAndContractKey(domainId, GATEWAY_GUARDS_KEY).isEmpty()) {
            registryService.create(new CreateContractCommand(
                    domainId,
                    GATEWAY_GUARDS_KEY,
                    "cross-portfolio-opportunity",
                    "Gateway enforcement demo (synthetic — §13 row 3c)",
                    "Synthetic guard-bearing contract for enforcement-gateway demos and the 3c"
                            + " acceptance. Not fixture content: both Beacon fixture contracts"
                            + " compile to zero guards (engine-spec contracts.md §16).",
                    null, null, null, null, null,
                    null,                               // proposedAt = seed clock
                    "gateway-demo-operator",
                    List.of(
                            new PartyCommand("Gateway Demo Operations",
                                    ContractPartyType.ORG_UNIT, null, true, List.of(
                                    new CommitmentCommand("Review enforcement decisions weekly",
                                            null, null))),
                            new PartyCommand("Gateway Demo Agent Fleet",
                                    ContractPartyType.EXTERNAL, null, true, List.of(
                                    new CommitmentCommand("Report utilization metrics through"
                                            + " the governed MCP surface", null, null)))),
                    List.of(new DeclineTriggerCommand(
                            "Gateway demo concluded — synthetic contract no longer needed",
                            null, null)),
                    List.of(),                          // no monitors — the gateway is the demo
                    List.of(
                            new GuardCommand("agent.promote", null, GuardEffect.DENY, null,
                                    "Agent promotion is blocked under this demo contract"
                                            + " (§13 3c check_action lane)"),
                            new GuardCommand(GovernedActions.CONTRACT_REPORT_METRIC, null,
                                    GuardEffect.DENY, null,
                                    "Metric reporting is blocked under this demo contract"
                                            + " (§13 3c REST/MCP deny lanes)")),
                    List.of()));
        }
        Contract contract = contractRepository
                .findByDomainIdAndContractKey(domainId, GATEWAY_GUARDS_KEY)
                .orElseThrow(() -> new IllegalStateException(
                        GATEWAY_GUARDS_KEY + " missing after seed"));
        for (ContractParty party
                : partyRepository.findByContractIdOrderBySequenceOrderAsc(contract.getId())) {
            if ("Gateway Demo Agent Fleet".equals(party.getPartyLabel())
                    && !GATEWAY_DEMO_PRINCIPAL.equals(party.getMcpPrincipalId())) {
                party.setMcpPrincipalId(GATEWAY_DEMO_PRINCIPAL);
                partyRepository.save(party);
            }
            if (party.isResponseRequired()) {
                registryService.respond(contract.getId(),
                        new ContractRegistryService.RespondCommand(
                                party.getId(), PartyResponse.ACCEPTED, null, null,
                                party.getPartyLabel(), ContractActor.human(party.getPartyLabel())));
            }
        }
        Contract after = contractRepository.findById(contract.getId()).orElseThrow();
        log.info("[seed]   gateway-guards lever: {} is {} (agent.promote + {} DENY guards live"
                        + " iff ACTIVE; agent-fleet party bound to principal '{}')",
                GATEWAY_GUARDS_KEY, after.getStatus(),
                GovernedActions.CONTRACT_REPORT_METRIC, GATEWAY_DEMO_PRINCIPAL);
    }

    /**
     * §14.2 activate lever: ACCEPTED responses for ALL required parties of
     * vector 1 through the real respond path — the LAST acceptance activates
     * (monitors ACTIVE, guards live) under the §10.1 row lock. Idempotent:
     * duplicate identical responses are §10.2 no-ops, so a re-boot with the
     * lever still on changes nothing.
     */
    private void activateCoTribal(UUID domainId) {
        Contract contract = contractRepository
                .findByDomainIdAndContractKey(domainId, CO_TRIBAL_KEY)
                .orElseThrow(() -> new IllegalStateException(
                        CO_TRIBAL_KEY + " missing after seed — cannot apply activate lever"));
        for (ContractParty party
                : partyRepository.findByContractIdOrderBySequenceOrderAsc(contract.getId())) {
            if (!party.isResponseRequired()) {
                continue;
            }
            registryService.respond(contract.getId(), new ContractRegistryService.RespondCommand(
                    party.getId(), PartyResponse.ACCEPTED, null, null,
                    // † responder identity = party label (seed-only fiction;
                    // the fixture defines no responder names).
                    party.getPartyLabel(), ContractActor.human(party.getPartyLabel())));
        }
        Contract after = contractRepository.findById(contract.getId()).orElseThrow();
        if (after.getStatus() == ContractStatus.ACTIVE) {
            log.info("[seed]   activate lever: {} is ACTIVE [activatedAt={}]",
                    CO_TRIBAL_KEY, after.getActivatedAt());
        } else {
            log.warn("[seed]   activate lever: {} did not activate [status={}]",
                    CO_TRIBAL_KEY, after.getStatus());
        }
    }
}
