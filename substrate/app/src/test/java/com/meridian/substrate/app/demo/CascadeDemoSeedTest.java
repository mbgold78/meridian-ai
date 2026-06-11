package com.meridian.substrate.app.demo;

import com.meridian.substrate.common.domain.Cascade;
import com.meridian.substrate.common.domain.CascadeLevel;
import com.meridian.substrate.common.domain.CascadeLevelAction;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.domain.enums.CascadeActionType;
import com.meridian.substrate.common.domain.enums.CascadeAttentionSignal;
import com.meridian.substrate.common.domain.enums.CascadeLevelState;
import com.meridian.substrate.common.domain.enums.CascadeState;
import com.meridian.substrate.common.repo.CascadeLevelActionRepository;
import com.meridian.substrate.common.repo.CascadeLevelRepository;
import com.meridian.substrate.common.repo.CascadeRepository;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.engine.cascade.CascadeActor;
import com.meridian.substrate.engine.cascade.CascadeLifecycleService;
import com.meridian.substrate.engine.cascade.exception.CascadeConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test plan #2 fixture vectors + #3 continuation vectors (engine-spec
 * cascade.md §12), seeded via the REAL DemoPack path: the context boots with
 * {@code substrate.demo.seed=true} so {@link SeedOrchestrator} replays the
 * three §11.4 homepoc fixtures through {@link CascadeDemoSeeder}'s
 * lifecycle-verb action streams (never row inserts, ADR D16).
 *
 * <p>#2 pins the §11.4 end states — level rows, states, rounds, frontier,
 * signal, chain_seq, and VERBATIM critique/reasoning text (the literals here
 * are deliberately duplicated from {@code cascades.ts}, independent of the
 * seeder's constants, so fixture drift in either place fails the pin).
 * Explicitly pinned: <b>{@code c-vendor-cost} is the decline-pending vector
 * and {@code c-tribal-succession} is all-cascaded/escalating</b> — guarding
 * against the 05-walkthrough narrative drift recurring in test authoring
 * (port-map §6 fixture caveat).
 *
 * <p>#3 extends the seeds in-test through the lifecycle service bean; each
 * continuation test is {@code @Transactional} so it rolls back to the
 * pristine seeded state. Post-terminal conflicts are asserted at the service
 * boundary ({@code CASCADE_TERMINAL}); the HTTP 409 envelope mapping is
 * chunk-5 scope (§12 build order).
 *
 * <p>Uses a private H2 database (the dev-profile default {@code mem:substrate}
 * is shared JVM-wide across cached test contexts). H2-green is never
 * acceptance — Testcontainers-Postgres in CI is the gate (ADR D9).
 */
@SpringBootTest(properties = {
        "substrate.demo.seed=true",
        "spring.datasource.url=jdbc:h2:mem:demoseed_cascade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class CascadeDemoSeedTest {

    // ----- c-vendor-cost verbatim text (cascades.ts) -----
    private static final String VENDOR_CRITIQUE = "Vendor costs across healthcare subsidiaries"
            + " grew 14% YoY against a 6% benchmark. Identify what's still being paid for that"
            + " AI-leverage has obsoleted. Surface the depopulation candidates.";
    private static final String VENDOR_CRITERIA = "Each affected subsidiary returns either"
            + " (a) a depopulation plan with named contracts and Q3 target dates, or (b) a"
            + " Decline with documented reasoning for why the contracts remain necessary.";
    private static final String VENDOR_L0_REFINED = "Vendor cost growth across healthcare subs"
            + " is 14% YoY. Identify obsolete contracts and depopulation candidates.";
    private static final String VENDOR_L1_REFINED = "We have three vendor contracts in HIM and"
            + " front-office stack that may overlap with our coding-suggestion agent and"
            + " eligibility-verifier scope. Have IT and HIM evaluate.";
    private static final String VENDOR_L2_DECLINE = "Vendor contract 4127 (HIM transcription)"
            + " cannot be retired this quarter. We do not yet have HIPAA validation on the"
            + " coding-suggestion agent in production mode; shadow-mode promotion is gated on"
            + " Compliance review (target Q4 2026). The vendor remains required until then.";

    // ----- c-tribal-succession verbatim text -----
    private static final String TRIBAL_CRITIQUE = "Eight single-owner processes are flagged"
            + " across the portfolio with no documented succession. Each affected subsidiary"
            + " identifies named successor candidates against the bench within 30 days, or"
            + " returns a documented Decline explaining why succession is structurally blocked.";
    private static final String TRIBAL_L0_REFINED = "Tribal-knowledge concentration is the"
            + " highest-frequency finding across the portfolio. Sub COOs identify successor"
            + " candidates per flagged process or document a structural Decline.";
    private static final String TRIBAL_L1_REFINED = "Specimen routing and pathologist review"
            + " are both single-owner with no documented backup. Lab Operations Director to"
            + " scope a 6-month cross-train rotation against the bench-layout knowledge base,"
            + " name two candidates per process by end of month.";
    private static final String TRIBAL_L2_REFINED = "Cross-train rotation scoped against"
            + " current MT roster. Two candidates identified for specimen routing; pathologist"
            + " review still gated on credentialing pathway. Bench-layout knowledge capture"
            + " sessions starting next week with senior MT.";

    // ----- c-tellix-velocity verbatim text -----
    private static final String TELLIX_CRITIQUE = "Tellix has been in Plug-In phase for 142"
            + " days against the 90-day target. Three other subs in Plug-In moved within the"
            + " window. Either name a structural blocker that justifies the over-run or commit"
            + " to a phase-completion date in the next 30 days.";
    private static final String TELLIX_L1_REFINED = "Capability inventory is 80% complete; the"
            + " remaining 20% sits in Engineering's custom-spec translation work where two"
            + " senior engineers carry the tribal knowledge. Engineering Director to scope a"
            + " 4-week capture sprint and return a Plug-In completion date.";

    private static final String ORIGINATOR = "Operating Partner";

    @Autowired MockMvc mvc;
    @Autowired DomainRepository domainRepository;
    @Autowired CascadeRepository cascadeRepository;
    @Autowired CascadeLevelRepository levelRepository;
    @Autowired CascadeLevelActionRepository actionRepository;
    @Autowired CascadeLifecycleService lifecycle;
    @Autowired CascadeDemoSeeder seeder;

    // ----------------------------------------------- #2 fixture-vector pins

    @Test
    void seedsExactlyTheThreeFixtureCascadesIntoTheDemoDomain() {
        UUID domainId = demoDomainId();
        List<Cascade> cascades = cascadeRepository.findByDomainId(domainId);
        assertThat(cascades).hasSize(3);
        assertThat(cascades).allSatisfy(c -> {
            assertThat(c.getCurrentState()).isEqualTo(CascadeState.IN_FLIGHT);
            assertThat(c.getOriginOwnerLabel()).isEqualTo(ORIGINATOR);
            assertThat(c.getResponseSlaDays()).isZero();   // §11.4 SLA disabled
            assertThat(c.isOverdue()).isFalse();
        });
        // The drift-guard pin (port-map §6): the DECLINE sits on c-vendor-cost,
        // NOT on c-tribal-succession.
        assertThat(byCritique(domainId, VENDOR_CRITIQUE).getAttentionSignal())
                .isEqualTo(CascadeAttentionSignal.DECLINE_PENDING);
        assertThat(byCritique(domainId, TRIBAL_CRITIQUE).getAttentionSignal())
                .isEqualTo(CascadeAttentionSignal.ESCALATING);
        assertThat(byCritique(domainId, TELLIX_CRITIQUE).getAttentionSignal())
                .isEqualTo(CascadeAttentionSignal.ESCALATING);
    }

    @Test
    void vendorCostIsTheTerminalDeclineVector() {
        Cascade c = byCritique(demoDomainId(), VENDOR_CRITIQUE);

        assertThat(c.getCurrentState()).isEqualTo(CascadeState.IN_FLIGHT);
        assertThat(c.getAttentionSignal()).isEqualTo(CascadeAttentionSignal.DECLINE_PENDING);
        assertThat(c.getChainSeq()).isEqualTo(3);
        assertThat(c.getFrontierDepth()).isEqualTo(2);
        assertThat(c.getFrontierState()).isEqualTo(CascadeLevelState.DECLINED);
        assertThat(c.getOriginatedAt()).isEqualTo(Instant.parse("2026-04-08T00:00:00Z"));
        assertThat(c.getOriginalCritique()).isEqualTo(VENDOR_CRITIQUE);       // I3 verbatim
        assertThat(c.getAcceptanceCriteria()).isEqualTo(VENDOR_CRITERIA);     // I3 verbatim
        assertThat(c.getResolvedAt()).isNull();
        assertThat(c.getResolutionNote()).isNull();
        assertThat(c.getAcceptedDeclineActionId()).isNull();

        List<CascadeLevel> levels = levelRepository.findByCascadeIdOrderByDepthAsc(c.getId());
        assertThat(levels).hasSize(3);

        CascadeLevel l0 = levels.get(0);
        assertThat(l0.getOwnerLabel()).isEqualTo(ORIGINATOR);
        assertThat(l0.getCurrentState()).isEqualTo(CascadeLevelState.CASCADED);
        assertThat(l0.getRound()).isEqualTo(1);
        assertThat(l0.getRefinedCritique()).isEqualTo(VENDOR_L0_REFINED);
        assertThat(l0.getActedAt()).isEqualTo(Instant.parse("2026-04-08T00:00:00Z"));

        CascadeLevel l1 = levels.get(1);
        assertThat(l1.getOwnerLabel()).isEqualTo("Curasync COO");
        assertThat(l1.getCurrentState()).isEqualTo(CascadeLevelState.CASCADED);
        assertThat(l1.getRound()).isEqualTo(1);
        assertThat(l1.getRefinedCritique()).isEqualTo(VENDOR_L1_REFINED);
        assertThat(l1.getDeclineReasoning()).isNull();
        assertThat(l1.getActivatedAt()).isEqualTo(Instant.parse("2026-04-08T00:00:00Z"));
        assertThat(l1.getActedAt()).isEqualTo(Instant.parse("2026-04-11T00:00:00Z"));
        assertThat(l1.getResponseDeadline()).isNull();   // SLA disabled

        CascadeLevel l2 = levels.get(2);
        assertThat(l2.getOwnerLabel()).isEqualTo("Curasync HIM Director");
        assertThat(l2.getCurrentState()).isEqualTo(CascadeLevelState.DECLINED);
        assertThat(l2.getRound()).isEqualTo(1);
        assertThat(l2.getRefinedCritique()).isNull();
        assertThat(l2.getDeclineReasoning()).isEqualTo(VENDOR_L2_DECLINE);
        assertThat(l2.getActivatedAt()).isEqualTo(Instant.parse("2026-04-11T00:00:00Z"));
        assertThat(l2.getActedAt()).isEqualTo(Instant.parse("2026-04-22T00:00:00Z"));
        assertThat(l2.isOverdue()).isFalse();

        List<CascadeLevelAction> actions =
                actionRepository.findByCascadeIdOrderByChainSeqAsc(c.getId());
        assertThat(actions).hasSize(3);
        assertThat(actions).extracting(CascadeLevelAction::getChainSeq).containsExactly(1, 2, 3);
        assertThat(actions).extracting(CascadeLevelAction::getAction).containsExactly(
                CascadeActionType.ISSUE, CascadeActionType.CASCADE, CascadeActionType.DECLINE);
        assertThat(actions).extracting(CascadeLevelAction::getActorLabel).containsExactly(
                ORIGINATOR, "Curasync COO", "Curasync HIM Director");
        assertThat(actions).extracting(CascadeLevelAction::getActedAt).containsExactly(
                Instant.parse("2026-04-08T00:00:00Z"),
                Instant.parse("2026-04-11T00:00:00Z"),
                Instant.parse("2026-04-22T00:00:00Z"));
        assertThat(actions.get(0).getNextOwnerLabel()).isEqualTo("Curasync COO");
        assertThat(actions.get(0).getRefinedCritique()).isEqualTo(VENDOR_L0_REFINED);
        assertThat(actions.get(1).getNextOwnerLabel()).isEqualTo("Curasync HIM Director");
        assertThat(actions.get(1).getRefinedCritique()).isEqualTo(VENDOR_L1_REFINED);
        assertThat(actions.get(2).getDeclineReasoning()).isEqualTo(VENDOR_L2_DECLINE);
        assertThat(actions.get(2).getNextOwnerLabel()).isNull();
        assertThat(actions.get(2).getRefinedCritique()).isNull();
    }

    @Test
    void tribalSuccessionIsAllCascadedEscalating() {
        Cascade c = byCritique(demoDomainId(), TRIBAL_CRITIQUE);

        assertThat(c.getCurrentState()).isEqualTo(CascadeState.IN_FLIGHT);
        assertThat(c.getAttentionSignal()).isEqualTo(CascadeAttentionSignal.ESCALATING);
        assertThat(c.getChainSeq()).isEqualTo(3);
        assertThat(c.getFrontierDepth()).isEqualTo(3);
        assertThat(c.getFrontierState()).isEqualTo(CascadeLevelState.LOOKING);
        assertThat(c.getOriginatedAt()).isEqualTo(Instant.parse("2026-04-12T00:00:00Z"));

        List<CascadeLevel> levels = levelRepository.findByCascadeIdOrderByDepthAsc(c.getId());
        assertThat(levels).hasSize(4);
        // Every acted level CASCADED, round 1, verbatim refinements at fixture dates.
        assertThat(levels.subList(0, 3)).allSatisfy(l -> {
            assertThat(l.getCurrentState()).isEqualTo(CascadeLevelState.CASCADED);
            assertThat(l.getRound()).isEqualTo(1);
            assertThat(l.getDeclineReasoning()).isNull();
        });
        assertThat(levels.get(0).getOwnerLabel()).isEqualTo(ORIGINATOR);
        assertThat(levels.get(0).getRefinedCritique()).isEqualTo(TRIBAL_L0_REFINED);
        assertThat(levels.get(0).getActedAt()).isEqualTo(Instant.parse("2026-04-12T00:00:00Z"));
        assertThat(levels.get(1).getOwnerLabel()).isEqualTo("Sentari COO");
        assertThat(levels.get(1).getRefinedCritique()).isEqualTo(TRIBAL_L1_REFINED);
        assertThat(levels.get(1).getActedAt()).isEqualTo(Instant.parse("2026-04-17T00:00:00Z"));
        assertThat(levels.get(2).getOwnerLabel()).isEqualTo("Sentari Lab Operations Director");
        assertThat(levels.get(2).getRefinedCritique()).isEqualTo(TRIBAL_L2_REFINED);
        assertThat(levels.get(2).getActedAt()).isEqualTo(Instant.parse("2026-04-24T00:00:00Z"));
        // Frontier LOOKING row the fixtures never contained (§11.2); owner is
        // the §11.4 † seed-only label.
        CascadeLevel l3 = levels.get(3);
        assertThat(l3.getCurrentState()).isEqualTo(CascadeLevelState.LOOKING);
        assertThat(l3.getRound()).isEqualTo(1);
        assertThat(l3.getOwnerLabel()).isEqualTo("Sentari Senior MT");
        assertThat(l3.getActivatedAt()).isEqualTo(Instant.parse("2026-04-24T00:00:00Z"));
        assertThat(l3.getActedAt()).isNull();
        assertThat(l3.getResponseDeadline()).isNull();

        assertThat(actionRepository.findByCascadeIdOrderByChainSeqAsc(c.getId()))
                .extracting(CascadeLevelAction::getAction)
                .containsExactly(CascadeActionType.ISSUE, CascadeActionType.CASCADE,
                        CascadeActionType.CASCADE);
    }

    @Test
    void tellixVelocityIsEscalatingAtL2() {
        Cascade c = byCritique(demoDomainId(), TELLIX_CRITIQUE);

        assertThat(c.getCurrentState()).isEqualTo(CascadeState.IN_FLIGHT);
        assertThat(c.getAttentionSignal()).isEqualTo(CascadeAttentionSignal.ESCALATING);
        assertThat(c.getChainSeq()).isEqualTo(2);
        assertThat(c.getFrontierDepth()).isEqualTo(2);
        assertThat(c.getFrontierState()).isEqualTo(CascadeLevelState.LOOKING);
        assertThat(c.getOriginatedAt()).isEqualTo(Instant.parse("2026-04-09T00:00:00Z"));

        List<CascadeLevel> levels = levelRepository.findByCascadeIdOrderByDepthAsc(c.getId());
        assertThat(levels).hasSize(3);
        assertThat(levels.get(1).getOwnerLabel()).isEqualTo("Tellix COO");
        assertThat(levels.get(1).getCurrentState()).isEqualTo(CascadeLevelState.CASCADED);
        assertThat(levels.get(1).getRefinedCritique()).isEqualTo(TELLIX_L1_REFINED);
        assertThat(levels.get(1).getActedAt()).isEqualTo(Instant.parse("2026-04-19T00:00:00Z"));
        CascadeLevel l2 = levels.get(2);
        assertThat(l2.getCurrentState()).isEqualTo(CascadeLevelState.LOOKING);
        assertThat(l2.getOwnerLabel()).isEqualTo("Tellix Engineering Director"); // §11.4 †
        assertThat(l2.getActivatedAt()).isEqualTo(Instant.parse("2026-04-19T00:00:00Z"));
        assertThat(l2.getActedAt()).isNull();
    }

    /**
     * The §12 user-runnable acceptance shape, pinned as a standing test:
     * 3 cascades in operator attention order with c-vendor-cost
     * decline-pending, and its timeline gapless from chain_seq 1.
     *
     * <p>The §11.4 binding action stream for c-vendor-cost is THREE actions
     * (issue → cascade → decline), so the timeline is chain_seq 1..3 — the
     * "chain_seq 1..4" line in the §12 snippet contradicts §11.4 and §6.1's
     * own CascadeAction sample (decline at chainSeq 3) and is spec drift, not
     * a missing action.
     */
    @Test
    void readApiServesTheSeedInOperatorAttentionOrder() throws Exception {
        UUID domainId = demoDomainId();
        Cascade vendor = byCritique(domainId, VENDOR_CRITIQUE);

        mvc.perform(get("/api/v1/cascades").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[0].id", is(vendor.getId().toString())))
                .andExpect(jsonPath("$.data[0].attentionSignal", is("decline-pending")))
                .andExpect(jsonPath("$.data[0].originatedBy", is(ORIGINATOR)))
                .andExpect(jsonPath("$.data[1].attentionSignal", is("escalating")))
                .andExpect(jsonPath("$.data[2].attentionSignal", is("escalating")));

        mvc.perform(get("/api/v1/cascades/{id}/actions", vendor.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[0].chainSeq", is(1)))
                .andExpect(jsonPath("$.data[0].action", is("issue")))
                .andExpect(jsonPath("$.data[1].chainSeq", is(2)))
                .andExpect(jsonPath("$.data[1].action", is("cascade")))
                .andExpect(jsonPath("$.data[2].chainSeq", is(3)))
                .andExpect(jsonPath("$.data[2].action", is("decline")));
    }

    /** Re-running the existing-DB seed path never duplicates a fixture. */
    @Test
    @Transactional
    void reseedIsIdempotentPerFixture() {
        UUID domainId = demoDomainId();
        SeedContext ctx = new SeedContext();
        ctx.setDomainId(domainId);

        seeder.seedSamplesForDomain(ctx, domainId);

        assertThat(cascadeRepository.findByDomainId(domainId)).hasSize(3);
        assertThat(byCritique(domainId, VENDOR_CRITIQUE).getChainSeq()).isEqualTo(3);
    }

    // -------------------------------------------- #3 continuation vectors
    // Each continuation extends the committed seed inside a rolled-back test
    // transaction, so every test starts from the pristine §11.4 end states.

    @Test
    @Transactional
    void continuationOriginatorResolvesDeclinedThenTerminalStateAbsorbs() {
        Cascade c = byCritique(demoDomainId(), VENDOR_CRITIQUE);
        UUID l2DeclineId = latestActionId(c.getId());

        Cascade resolved = lifecycle.resolve(c.getId(), new CascadeLifecycleService.ResolveCommand(
                key(), null, CascadeActor.of(ORIGINATOR),
                CascadeLifecycleService.Resolution.DECLINED,
                "Decline accepted — contract 4127 stands until Q4 HIPAA validation",
                l2DeclineId));

        // resolved-declined is a peer terminal outcome, never failure
        // vocabulary (ADR-001 non-negotiable #1).
        assertThat(resolved.getCurrentState()).isEqualTo(CascadeState.RESOLVED_DECLINED);
        assertThat(resolved.getAttentionSignal()).isEqualTo(CascadeAttentionSignal.RESOLVED_DECLINED);
        assertThat(resolved.getChainSeq()).isEqualTo(4);
        assertThat(resolved.getResolvedAt()).isNotNull();
        assertThat(resolved.getResolvedByLabel()).isEqualTo(ORIGINATOR);
        assertThat(resolved.getResolutionNote())
                .isEqualTo("Decline accepted — contract 4127 stands until Q4 HIPAA validation");
        assertThat(resolved.getAcceptedDeclineActionId()).isEqualTo(l2DeclineId);

        // Terminal absorption (§5.2): any further action → CASCADE_TERMINAL (409).
        assertThatThrownBy(() -> lifecycle.absorb(c.getId(), 1,
                new CascadeLifecycleService.AbsorbCommand(key(), null,
                        CascadeActor.of("Curasync COO"), "Too late — already resolved")))
                .isInstanceOfSatisfying(CascadeConflictException.class, ex ->
                        assertThat(ex.getCode())
                                .isEqualTo(CascadeConflictException.Code.CASCADE_TERMINAL));
    }

    @Test
    @Transactional
    void continuationEndorseDeclineThenOriginatorAcceptsTheL1Decline() {
        Cascade c = byCritique(demoDomainId(), VENDOR_CRITIQUE);

        // L4 endorse-decline: the CASCADED level above the DECLINED frontier
        // declines at its own level with its own mandatory reasoning (ADR D4).
        Cascade endorsed = lifecycle.decline(c.getId(), 1,
                new CascadeLifecycleService.DeclineCommand(key(), null,
                        CascadeActor.of("Curasync COO"),
                        "HIM is right — the HIPAA gate holds portfolio-wide this quarter"));
        assertThat(endorsed.getFrontierDepth()).isEqualTo(1);
        assertThat(endorsed.getFrontierState()).isEqualTo(CascadeLevelState.DECLINED);
        assertThat(endorsed.getAttentionSignal()).isEqualTo(CascadeAttentionSignal.DECLINE_PENDING);
        assertThat(endorsed.getChainSeq()).isEqualTo(4);
        UUID l1DeclineId = latestActionId(c.getId());

        Cascade resolved = lifecycle.resolve(c.getId(), new CascadeLifecycleService.ResolveCommand(
                key(), null, CascadeActor.of(ORIGINATOR),
                CascadeLifecycleService.Resolution.DECLINED,
                "Endorsed decline accepted at L1", l1DeclineId));

        assertThat(resolved.getCurrentState()).isEqualTo(CascadeState.RESOLVED_DECLINED);
        assertThat(resolved.getAcceptedDeclineActionId()).isEqualTo(l1DeclineId);
        // The decline run stays honestly visible: both levels DECLINED.
        List<CascadeLevel> levels = levelRepository.findByCascadeIdOrderByDepthAsc(c.getId());
        assertThat(levels.get(1).getCurrentState()).isEqualTo(CascadeLevelState.DECLINED);
        assertThat(levels.get(2).getCurrentState()).isEqualTo(CascadeLevelState.DECLINED);
    }

    @Test
    @Transactional
    void continuationRecascadeReactivatesL2AsRound2ThenDeepens() {
        Cascade c = byCritique(demoDomainId(), VENDOR_CRITIQUE);

        // L5: revised refinement reactivates the declined L2 as round 2.
        Cascade recascaded = lifecycle.recascade(c.getId(), 1,
                new CascadeLifecycleService.RecascadeCommand(key(), null,
                        CascadeActor.of("Curasync COO"),
                        "Scope narrowed: evaluate only the two non-HIPAA-gated contracts",
                        null, "Curasync HIM Director"));
        assertThat(recascaded.getFrontierDepth()).isEqualTo(2);
        assertThat(recascaded.getFrontierState()).isEqualTo(CascadeLevelState.LOOKING);
        assertThat(recascaded.getAttentionSignal()).isEqualTo(CascadeAttentionSignal.ESCALATING);
        assertThat(recascaded.getChainSeq()).isEqualTo(4);

        List<CascadeLevel> levels = levelRepository.findByCascadeIdOrderByDepthAsc(c.getId());
        CascadeLevel l2 = levels.get(2);
        assertThat(l2.getCurrentState()).isEqualTo(CascadeLevelState.LOOKING);
        assertThat(l2.getRound()).isEqualTo(2);
        // §5.1 round semantics: projections reset; the round-1 decline remains
        // only in the action log.
        assertThat(l2.getDeclineReasoning()).isNull();
        assertThat(l2.getRefinedCritique()).isNull();
        assertThat(l2.getActedAt()).isNull();

        // Deepening after recascade: L2 (round 2) cascades to a new L3.
        Cascade deepened = lifecycle.cascade(c.getId(), 2,
                new CascadeLifecycleService.CascadeCommand(key(), null,
                        CascadeActor.of("Curasync HIM Director"),
                        "Front-office eligibility contract overlap confirmed — coding team to verify",
                        null, "Curasync Coding Team Lead"));
        assertThat(deepened.getFrontierDepth()).isEqualTo(3);
        assertThat(deepened.getFrontierState()).isEqualTo(CascadeLevelState.LOOKING);
        assertThat(deepened.getChainSeq()).isEqualTo(5);

        levels = levelRepository.findByCascadeIdOrderByDepthAsc(c.getId());
        assertThat(levels).hasSize(4);
        assertThat(levels.get(2).getCurrentState()).isEqualTo(CascadeLevelState.CASCADED);
        assertThat(levels.get(2).getRound()).isEqualTo(2);
        assertThat(levels.get(3).getCurrentState()).isEqualTo(CascadeLevelState.LOOKING);
        assertThat(levels.get(3).getRound()).isEqualTo(1);
        assertThat(levels.get(3).getOwnerLabel()).isEqualTo("Curasync Coding Team Lead");
    }

    @Test
    @Transactional
    void continuationAbsorbOverDeclineFlipsTheSignalToAbsorbed() {
        Cascade c = byCritique(demoDomainId(), VENDOR_CRITIQUE);

        // L6 absorb-over-decline: L1 takes the work; L2 stays DECLINED in
        // history — its decline is answered, no longer pending (§5.3).
        Cascade absorbed = lifecycle.absorb(c.getId(), 1,
                new CascadeLifecycleService.AbsorbCommand(key(), null,
                        CascadeActor.of("Curasync COO"),
                        "COO office takes the depopulation review directly"));

        assertThat(absorbed.getCurrentState()).isEqualTo(CascadeState.IN_FLIGHT);
        assertThat(absorbed.getFrontierDepth()).isEqualTo(1);
        assertThat(absorbed.getFrontierState()).isEqualTo(CascadeLevelState.ABSORBED);
        assertThat(absorbed.getAttentionSignal()).isEqualTo(CascadeAttentionSignal.ABSORBED);
        assertThat(absorbed.getChainSeq()).isEqualTo(4);

        List<CascadeLevel> levels = levelRepository.findByCascadeIdOrderByDepthAsc(c.getId());
        assertThat(levels.get(1).getCurrentState()).isEqualTo(CascadeLevelState.ABSORBED);
        assertThat(levels.get(1).getRefinedCritique())
                .isEqualTo("COO office takes the depopulation review directly");
        assertThat(levels.get(2).getCurrentState()).isEqualTo(CascadeLevelState.DECLINED);
        assertThat(levels.get(2).getDeclineReasoning()).isEqualTo(VENDOR_L2_DECLINE);
    }

    @Test
    @Transactional
    void continuationTellixResolvesLooksGoodAndIsTerminal() {
        Cascade c = byCritique(demoDomainId(), TELLIX_CRITIQUE);

        // A2: originator short-circuit at any time in-flight — here over a
        // LOOKING frontier; the LOOKING row remains as honest history.
        Cascade resolved = lifecycle.resolve(c.getId(), new CascadeLifecycleService.ResolveCommand(
                key(), null, CascadeActor.of(ORIGINATOR),
                CascadeLifecycleService.Resolution.LOOKS_GOOD,
                "Capture sprint scoped and committed — completion date accepted", null));

        assertThat(resolved.getCurrentState()).isEqualTo(CascadeState.RESOLVED_LOOKS_GOOD);
        assertThat(resolved.getAttentionSignal())
                .isEqualTo(CascadeAttentionSignal.RESOLVED_LOOKS_GOOD);
        assertThat(resolved.getResolutionNote())
                .isEqualTo("Capture sprint scoped and committed — completion date accepted");
        assertThat(resolved.getResolvedByLabel()).isEqualTo(ORIGINATOR);
        assertThat(resolved.getAcceptedDeclineActionId()).isNull();
        assertThat(resolved.getChainSeq()).isEqualTo(3);
        assertThat(levelRepository.findByCascadeIdOrderByDepthAsc(c.getId()).get(2)
                .getCurrentState()).isEqualTo(CascadeLevelState.LOOKING);

        // Both terminal states absorb equally (§5.2).
        assertThatThrownBy(() -> lifecycle.cascade(c.getId(), 2,
                new CascadeLifecycleService.CascadeCommand(key(), null,
                        CascadeActor.of("Tellix Engineering Director"),
                        "Post-terminal refinement", null, "Tellix Staff Engineer")))
                .isInstanceOfSatisfying(CascadeConflictException.class, ex ->
                        assertThat(ex.getCode())
                                .isEqualTo(CascadeConflictException.Code.CASCADE_TERMINAL));
    }

    // ----------------------------------------------------------------- helpers

    private UUID demoDomainId() {
        // The PRIMARY demo domain — since Phase 6a the AgentDemoSeeder also
        // creates one Beacon SATELLITE domain per fixture subsidiary (a Beacon
        // subsidiary IS a Domain — engine-spec agents.md §15), marked
        // metadata.beaconSubsidiary; the cascade fixtures live in the primary
        // domain only.
        List<Domain> primaries = domainRepository.findAll().stream()
                .filter(Domain::isDemo)
                .filter(d -> d.getMetadata() == null
                        || !d.getMetadata().path("beaconSubsidiary").asBoolean(false))
                .toList();
        assertThat(primaries).hasSize(1);
        return primaries.get(0).getId();
    }

    private Cascade byCritique(UUID domainId, String originalCritique) {
        return cascadeRepository.findByDomainId(domainId).stream()
                .filter(c -> originalCritique.equals(c.getOriginalCritique()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "fixture cascade not seeded: " + originalCritique.substring(0, 40) + "…"));
    }

    private UUID latestActionId(UUID cascadeId) {
        return actionRepository.findFirstByCascadeIdOrderByChainSeqDesc(cascadeId)
                .orElseThrow().getId();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
