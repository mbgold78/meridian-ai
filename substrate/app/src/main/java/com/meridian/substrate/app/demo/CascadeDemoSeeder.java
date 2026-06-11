package com.meridian.substrate.app.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.Cascade;
import com.meridian.substrate.common.domain.CascadeDraft;
import com.meridian.substrate.common.domain.enums.CascadeAuthoringMode;
import com.meridian.substrate.common.domain.enums.CascadeDraftStatus;
import com.meridian.substrate.common.repo.CascadeDraftRepository;
import com.meridian.substrate.common.repo.CascadeLevelActionRepository;
import com.meridian.substrate.common.repo.CascadeLevelRepository;
import com.meridian.substrate.common.repo.CascadeRepository;
import com.meridian.substrate.engine.cascade.CascadeActor;
import com.meridian.substrate.engine.cascade.CascadeAttestationHook;
import com.meridian.substrate.engine.cascade.CascadeLifecycleService;
import com.meridian.substrate.engine.cascade.CascadeNotificationService;
import com.meridian.substrate.engine.cascade.CascadeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * DemoPack cascade seed (engine-spec cascade.md §11.4; ADR-001 D16): replays
 * the three homepoc Beacon fixtures from
 * {@code meridian-homepoc/frontend/src/demo/beacon/cascades.ts} by DRIVING the
 * real {@link CascadeLifecycleService} verb path — draft → issue → cascade →
 * … → decline — with a fixed clock per action, <b>never row inserts</b>, so
 * the seed is also a standing integration test of the state machine.
 *
 * <p>Gating: the {@link SeedOrchestrator} runs seeders only when
 * {@code substrate.demo.seed=true} and only into {@code isDemo} domains
 * (bulk path creates the demo domain; the existing-DB path iterates demo
 * domains exclusively). Demo data never ships to a customer build
 * (port-map trap #9).
 *
 * <p>Fixture-fidelity notes (binding, §11.4):
 * <ul>
 *   <li><b>{@code c-vendor-cost} is the terminal-decline vector — NOT
 *       {@code c-tribal-succession}</b> (the 05-walkthrough narrative drifted;
 *       port-map §6 fixture caveat, verified against {@code cascades.ts}).</li>
 *   <li>{@code c-tribal-succession} ends all-cascaded with frontier L3
 *       LOOKING (escalating); {@code c-tellix-velocity} ends with frontier L2
 *       LOOKING (escalating).</li>
 *   <li>Original critiques, acceptance criteria, refined critiques, decline
 *       reasoning and the L0 refined one-liners are copied VERBATIM from
 *       {@code cascades.ts}.</li>
 *   <li>The two frontier owner labels the fixtures omit (fixtures never
 *       render LOOKING rows) — "Sentari Senior MT" and "Tellix Engineering
 *       Director" — are derived from fixture narrative text and are flagged
 *       seed-only fiction (§11.4 †).</li>
 *   <li>All seeds use {@code responseSlaDays=0} (SLA disabled — keeps the
 *       demo deterministic; overdue paths are covered by tests, not demo
 *       data; ADR D5).</li>
 *   <li>Owner labels are resolved against seeded {@code organization_members}
 *       by name within the demo domain when present, else label-only. The
 *       shipped DemoPack org is the Meridian Bancorp risk org, so every
 *       Beacon label resolves label-only today; the lookup honors §11.4
 *       should the demo org ever grow these names.</li>
 *   <li>{@code affectedDomainId} stays NULL: a Beacon subsidiary IS a Domain
 *       (§11.3), but the DemoPack seeds exactly one demo domain and the
 *       Beacon subsidiary rows are frontend-fixture-only — fabricating
 *       Domain rows for them is not part of the §11.4 seed.</li>
 * </ul>
 *
 * <p>Mechanics: drafts are created via the repository — {@code cascade_drafts}
 * is mutable authoring persistence OUTSIDE the audit trail (ADR D6;
 * {@code CascadeDraftService} is chunk-7 scope per the §12 build order) — and
 * every cascade/level/action row flows exclusively through the lifecycle
 * verbs. The seeder builds a private {@link CascadeLifecycleService} instance
 * over the shared repository beans with a stepping fixed clock (fixture seam
 * S15) so each action lands at its fixture date; being hand-constructed, its
 * {@code @Transactional} annotations are unproxied and the verbs join the
 * orchestrator's single seed transaction (the same pattern as the engine test
 * slices driving verbs under an explicit transaction). Idempotent re-entry is
 * per-fixture-absent, keyed on the (immutable, invariant I3) original
 * critique within the domain.
 */
@Component
public class CascadeDemoSeeder implements Seeder {

    private static final Logger log = LoggerFactory.getLogger(CascadeDemoSeeder.class);

    static final String ORIGINATOR = "Operating Partner";

    private final CascadeRepository cascadeRepository;
    private final CascadeDraftRepository draftRepository;
    private final SteppingClock clock = new SteppingClock();
    private final CascadeLifecycleService lifecycle;

    public CascadeDemoSeeder(CascadeRepository cascadeRepository,
                             CascadeLevelRepository levelRepository,
                             CascadeLevelActionRepository actionRepository,
                             CascadeDraftRepository draftRepository,
                             CascadeNotificationService notificationService,
                             CascadeAttestationHook attestationHook,
                             CascadeProperties properties,
                             ObjectMapper objectMapper) {
        this.cascadeRepository = cascadeRepository;
        this.draftRepository = draftRepository;
        this.lifecycle = new CascadeLifecycleService(cascadeRepository, levelRepository,
                actionRepository, draftRepository, notificationService, attestationHook,
                properties, clock, objectMapper);
    }

    @Override
    public void seedBulk(SeedContext ctx) {
        seedFixtures(ctx.getDomainId(), ctx.membersByName());
    }

    /**
     * Existing-DB idempotent path: pre-Phase-2 dev DBs pick up the cascade
     * fixtures without a wipe (per-fixture-absent re-entry). The orchestrator
     * only ever passes {@code isDemo} domains here.
     */
    @Override
    public void seedSamplesForDomain(SeedContext ctx, UUID domainId) {
        seedFixtures(domainId, ctx.membersByName());
    }

    @Override
    public List<Class<? extends Seeder>> dependsOn() {
        return List.of(DomainSeeder.class, OrganizationSeeder.class);
    }

    // ------------------------------------------------------------ the fixtures

    private void seedFixtures(UUID domainId, Map<String, UUID> membersByName) {
        Set<String> existingCritiques = new HashSet<>();
        for (Cascade existing : cascadeRepository.findByDomainId(domainId)) {
            existingCritiques.add(existing.getOriginalCritique());
        }
        int seeded = 0;
        seeded += seedVendorCost(domainId, membersByName, existingCritiques) ? 1 : 0;
        seeded += seedTribalSuccession(domainId, membersByName, existingCritiques) ? 1 : 0;
        seeded += seedTellixVelocity(domainId, membersByName, existingCritiques) ? 1 : 0;
        log.info("[seed] CascadeDemoSeeder: {} of 3 fixture cascades seeded via the lifecycle"
                + " verb path [domainId={}] (0 = already present, idempotent re-entry)",
                seeded, domainId);
    }

    /**
     * {@code c-vendor-cost} — issue 04-08 (L1: Curasync COO) → L1 cascade
     * 04-11 (→ Curasync HIM Director) → L2 DECLINE 04-22. End state:
     * in-flight, frontier L2 DECLINED, signal {@code decline-pending} — the
     * terminal-decline test vector.
     */
    private boolean seedVendorCost(UUID domainId, Map<String, UUID> members, Set<String> existing) {
        String originalCritique = "Vendor costs across healthcare subsidiaries grew 14% YoY"
                + " against a 6% benchmark. Identify what's still being paid for that AI-leverage"
                + " has obsoleted. Surface the depopulation candidates.";
        if (existing.contains(originalCritique)) {
            return false;
        }
        Cascade c = issueFixture(domainId, members, "c-vendor-cost",
                "2026-04-08T00:00:00Z",
                originalCritique,
                "Each affected subsidiary returns either (a) a depopulation plan with named"
                        + " contracts and Q3 target dates, or (b) a Decline with documented"
                        + " reasoning for why the contracts remain necessary.",
                "Vendor cost growth across healthcare subs is 14% YoY. Identify obsolete"
                        + " contracts and depopulation candidates.",
                "Curasync COO");
        cascadeStep(c.getId(), 1, "2026-04-11T00:00:00Z", "Curasync COO",
                "We have three vendor contracts in HIM and front-office stack that may overlap"
                        + " with our coding-suggestion agent and eligibility-verifier scope."
                        + " Have IT and HIM evaluate.",
                "Curasync HIM Director", members, "c-vendor-cost:l1-cascade");
        c = declineStep(c.getId(), 2, "2026-04-22T00:00:00Z", "Curasync HIM Director",
                "Vendor contract 4127 (HIM transcription) cannot be retired this quarter."
                        + " We do not yet have HIPAA validation on the coding-suggestion agent"
                        + " in production mode; shadow-mode promotion is gated on Compliance"
                        + " review (target Q4 2026). The vendor remains required until then.",
                "c-vendor-cost:l2-decline");
        logSeeded("c-vendor-cost", c);
        return true;
    }

    /**
     * {@code c-tribal-succession} — issue 04-12 (L1: Sentari COO) → L1
     * cascade 04-17 (→ Sentari Lab Operations Director) → L2 cascade 04-24
     * (→ "Sentari Senior MT" †). End state: in-flight, frontier L3 LOOKING,
     * signal {@code escalating} — all acted levels CASCADED (the
     * 05-walkthrough narrative drifted; this is NOT the decline vector).
     */
    private boolean seedTribalSuccession(UUID domainId, Map<String, UUID> members,
                                         Set<String> existing) {
        String originalCritique = "Eight single-owner processes are flagged across the portfolio"
                + " with no documented succession. Each affected subsidiary identifies named"
                + " successor candidates against the bench within 30 days, or returns a"
                + " documented Decline explaining why succession is structurally blocked.";
        if (existing.contains(originalCritique)) {
            return false;
        }
        Cascade c = issueFixture(domainId, members, "c-tribal-succession",
                "2026-04-12T00:00:00Z",
                originalCritique,
                "Each affected subsidiary returns either (a) a named successor candidate per"
                        + " flagged process with onboarding timeline, or (b) a Decline citing"
                        + " the structural barrier (regulated-role pool too thin,"
                        + " acquired-process consolidation pending, or methodology gap).",
                "Tribal-knowledge concentration is the highest-frequency finding across the"
                        + " portfolio. Sub COOs identify successor candidates per flagged"
                        + " process or document a structural Decline.",
                "Sentari COO");
        cascadeStep(c.getId(), 1, "2026-04-17T00:00:00Z", "Sentari COO",
                "Specimen routing and pathologist review are both single-owner with no"
                        + " documented backup. Lab Operations Director to scope a 6-month"
                        + " cross-train rotation against the bench-layout knowledge base, name"
                        + " two candidates per process by end of month.",
                "Sentari Lab Operations Director", members, "c-tribal-succession:l1-cascade");
        // "Sentari Senior MT": seed-only fiction (§11.4 †) — the fixtures omit
        // LOOKING rows; the label is derived from the L2 narrative ("knowledge
        // capture sessions starting next week with senior MT").
        c = cascadeStep(c.getId(), 2, "2026-04-24T00:00:00Z", "Sentari Lab Operations Director",
                "Cross-train rotation scoped against current MT roster. Two candidates"
                        + " identified for specimen routing; pathologist review still gated on"
                        + " credentialing pathway. Bench-layout knowledge capture sessions"
                        + " starting next week with senior MT.",
                "Sentari Senior MT", members, "c-tribal-succession:l2-cascade");
        logSeeded("c-tribal-succession", c);
        return true;
    }

    /**
     * {@code c-tellix-velocity} — issue 04-09 (L1: Tellix COO) → L1 cascade
     * 04-19 (→ "Tellix Engineering Director" †). End state: in-flight,
     * frontier L2 LOOKING, signal {@code escalating}.
     */
    private boolean seedTellixVelocity(UUID domainId, Map<String, UUID> members,
                                       Set<String> existing) {
        String originalCritique = "Tellix has been in Plug-In phase for 142 days against the"
                + " 90-day target. Three other subs in Plug-In moved within the window. Either"
                + " name a structural blocker that justifies the over-run or commit to a"
                + " phase-completion date in the next 30 days.";
        if (existing.contains(originalCritique)) {
            return false;
        }
        Cascade c = issueFixture(domainId, members, "c-tellix-velocity",
                "2026-04-09T00:00:00Z",
                originalCritique,
                "Tellix returns either (a) a phase-completion commitment with named owner and"
                        + " target date within 30 days, or (b) a Decline documenting the"
                        + " structural blocker (regulated-process inventory still inflight,"
                        + " capacity constraint with named hire pending, or methodology gap"
                        + " requiring Beacon support).",
                "Tellix Plug-In over-run is 52 days past target with no named blocker on the"
                        + " dashboard. COO returns either a completion commitment or a"
                        + " documented structural Decline.",
                "Tellix COO");
        // "Tellix Engineering Director": seed-only fiction (§11.4 †) — derived
        // from the L1 narrative ("Engineering Director to scope a 4-week
        // capture sprint").
        c = cascadeStep(c.getId(), 1, "2026-04-19T00:00:00Z", "Tellix COO",
                "Capability inventory is 80% complete; the remaining 20% sits in Engineering's"
                        + " custom-spec translation work where two senior engineers carry the"
                        + " tribal knowledge. Engineering Director to scope a 4-week capture"
                        + " sprint and return a Plug-In completion date.",
                "Tellix Engineering Director", members, "c-tellix-velocity:l1-cascade");
        logSeeded("c-tellix-velocity", c);
        return true;
    }

    // ----------------------------------------------------------- verb helpers

    /** Draft (mutable authoring row, ADR D6) + ISSUE at the fixture date. */
    private Cascade issueFixture(UUID domainId, Map<String, UUID> members, String fixtureId,
                                 String issuedAt, String originalCritique,
                                 String acceptanceCriteria, String l0RefinedCritique,
                                 String level1Owner) {
        CascadeDraft draft = new CascadeDraft();
        draft.setDomainId(domainId);
        draft.setAuthorMemberId(members.get(ORIGINATOR));
        draft.setAuthorLabel(ORIGINATOR);
        draft.setOriginalCritique(originalCritique);
        draft.setAcceptanceCriteria(acceptanceCriteria);
        draft.setAuthoringMode(CascadeAuthoringMode.FREE_FORM);
        draft.setStatus(CascadeDraftStatus.OPEN);
        // §11.4: SLA disabled on every seed — deterministic demo, no overdue.
        draft.setResponseSlaDays(0);
        draft.setCreatedBy("seed");
        draft = draftRepository.save(draft);

        clock.set(Instant.parse(issuedAt));
        return lifecycle.issue(draft.getId(), new CascadeLifecycleService.IssueCommand(
                key(fixtureId, "issue"),
                new CascadeActor(members.get(ORIGINATOR), ORIGINATOR),
                level1Owner, members.get(level1Owner), l0RefinedCritique));
    }

    private Cascade cascadeStep(UUID cascadeId, int depth, String actedAt, String actor,
                                String refinedCritique, String nextOwner,
                                Map<String, UUID> members, String keySuffix) {
        clock.set(Instant.parse(actedAt));
        return lifecycle.cascade(cascadeId, depth, new CascadeLifecycleService.CascadeCommand(
                key(keySuffix), null, new CascadeActor(members.get(actor), actor),
                refinedCritique, members.get(nextOwner), nextOwner));
    }

    private Cascade declineStep(UUID cascadeId, int depth, String actedAt, String actor,
                                String declineReasoning, String keySuffix) {
        clock.set(Instant.parse(actedAt));
        // A legitimate decline — a documented accountability act flowing
        // through the same verb path as every other action, never an error
        // (ADR-001 non-negotiable #1).
        return lifecycle.decline(cascadeId, depth, new CascadeLifecycleService.DeclineCommand(
                key(keySuffix), null, CascadeActor.of(actor), declineReasoning));
    }

    private static String key(String fixtureId, String step) {
        return key(fixtureId + ":" + step);
    }

    /** Deterministic seed idempotency keys (unique per cascade, uq_cla_idem). */
    private static String key(String suffix) {
        return "demo:" + suffix;
    }

    private static void logSeeded(String fixtureId, Cascade c) {
        log.info("[seed]   {} [id={}, state={}, signal={}, frontier=L{} {}, chainSeq={}]",
                fixtureId, c.getId(), c.getCurrentState().wireValue(),
                c.getAttentionSignal().wireValue(), c.getFrontierDepth(),
                c.getFrontierState().wireValue(), c.getChainSeq());
    }

    /**
     * Fixed clock, re-pinned before each seed action (fixture seam S15) so
     * every {@code acted_at} / activation timestamp lands exactly on its
     * fixture date (00:00Z, §11.4). Zone-pinned UTC; {@code withZone} is
     * unsupported by design — nothing in the verb path requests a re-zoned
     * view, and silently returning the same mutable instance would alias two
     * clocks.
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
