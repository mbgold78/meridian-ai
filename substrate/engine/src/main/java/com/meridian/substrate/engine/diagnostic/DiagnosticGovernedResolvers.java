package com.meridian.substrate.engine.diagnostic;

import com.meridian.substrate.common.repo.AssertionRepository;
import com.meridian.substrate.common.repo.FindingRepository;
import com.meridian.substrate.common.repo.SynthesisRunRepository;
import com.meridian.substrate.engine.contract.gateway.GovernedContextResolver;
import com.meridian.substrate.engine.contract.gateway.GovernedContextResolver.GovernedContext;
import com.meridian.substrate.engine.contract.gateway.TargetRef;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/**
 * The diagnostic engine's explicit per-service {@code GovernedContextResolver}
 * beans (engine-spec contracts.md §4.2 PEP 2; diagnostic.md test plan #15 —
 * every §10 write verb is {@code @Governed}, NO {@code @Ungoverned} exists in
 * {@code engine.diagnostic}: diagnostics run on possibly-ACTIVE engagements,
 * so unlike D3-ingestion the CLI gets no exemption, ADR-004 D12).
 *
 * <p>Principal posture: the run-lifecycle verbs arrive as HUMAN persona
 * principals via REST or as SERVICE from the CLI runner / async run loop
 * (the aspect's documented fallback); {@code diagnostic.commit_findings} is
 * always SERVICE — the in-process lane's own write applied by substrate.
 */
@Configuration
public class DiagnosticGovernedResolvers {

    /** §4.3/§4.4 target vocabulary for diagnostic governance. */
    public static final String TARGET_DIAGNOSTIC_RUN = "DIAGNOSTIC_RUN";
    public static final String TARGET_FINDING = "FINDING";
    public static final String TARGET_DRAFT = "DRAFT";

    @Bean
    public GovernedContextResolver diagnosticRunGovernedResolver(SynthesisRunRepository runs) {
        return GovernedContextResolver.of(DiagnosticRunService.class,
                (actionType, method, args) -> {
                    if ("startRun".equals(method.getName())
                            && args.length > 0
                            && args[0] instanceof DiagnosticRunService.StartCommand cmd) {
                        return new GovernedContext(cmd.domainId(), null, null);
                    }
                    // resume / cancel lead with the run id.
                    if (!(args.length > 0 && args[0] instanceof UUID runId)) {
                        return GovernedContext.unresolved();
                    }
                    return runs.findById(runId)
                            .map(run -> new GovernedContext(run.getDomainId(),
                                    new TargetRef(TARGET_DIAGNOSTIC_RUN, runId.toString()), null))
                            .orElseGet(GovernedContext::unresolved);
                });
    }

    @Bean
    public GovernedContextResolver findingCommitGovernedResolver(SynthesisRunRepository runs) {
        return GovernedContextResolver.of(FindingCommitService.class,
                (actionType, method, args) -> {
                    // commitBatch leads with the run id.
                    if (!(args.length > 0 && args[0] instanceof UUID runId)) {
                        return GovernedContext.unresolved();
                    }
                    return runs.findById(runId)
                            .map(run -> new GovernedContext(run.getDomainId(),
                                    new TargetRef(TARGET_DIAGNOSTIC_RUN, runId.toString()), null))
                            .orElseGet(GovernedContext::unresolved);
                });
    }

    /** Curation verbs (finding.accept / finding.reject) lead with the finding id (spec §9). */
    @Bean
    public GovernedContextResolver curationGovernedResolver(FindingRepository findings) {
        return GovernedContextResolver.of(CurationService.class,
                (actionType, method, args) -> {
                    if (!(args.length > 0 && args[0] instanceof UUID findingId)) {
                        return GovernedContext.unresolved();
                    }
                    return findings.findById(findingId)
                            .map(finding -> new GovernedContext(finding.getDomainId(),
                                    new TargetRef(TARGET_FINDING, findingId.toString()), null))
                            .orElseGet(GovernedContext::unresolved);
                });
    }

    /** Draft verbs (capability.commit_draft / capability.reject_draft) lead with the assertion id (spec §5.3). */
    @Bean
    public GovernedContextResolver draftCommitGovernedResolver(AssertionRepository assertions) {
        return GovernedContextResolver.of(DraftCommitService.class,
                (actionType, method, args) -> {
                    if (!(args.length > 0 && args[0] instanceof UUID assertionId)) {
                        return GovernedContext.unresolved();
                    }
                    return assertions.findById(assertionId)
                            .map(assertion -> new GovernedContext(assertion.getDomainId(),
                                    new TargetRef(TARGET_DRAFT, assertionId.toString()), null))
                            .orElseGet(GovernedContext::unresolved);
                });
    }
}
