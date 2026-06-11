package com.meridian.substrate.app.config;

import com.meridian.substrate.app.api.v1.request.ReaderRegistrationCreateRequest;
import com.meridian.substrate.app.core.service.PhaseStateService;
import com.meridian.substrate.app.core.service.ProcessStepService;
import com.meridian.substrate.app.core.service.ReaderRegistrationService;
import com.meridian.substrate.common.repo.ProcessRepository;
import com.meridian.substrate.common.repo.ProcessStepRepository;
import com.meridian.substrate.common.repo.ReaderRegistrationRepository;
import com.meridian.substrate.engine.contract.gateway.GovernedContextResolver;
import com.meridian.substrate.engine.contract.gateway.GovernedContextResolver.GovernedContext;
import com.meridian.substrate.engine.contract.gateway.TargetRef;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/**
 * Explicit per-service {@code GovernedContextResolver} beans for the governed
 * {@code app.core.service} mutating commands (engine-spec contracts.md §4.2
 * PEP 2; the §14.6 ArchUnit governed-coverage rule pins the package). Domain
 * resolution follows the entity chain the method itself walks (step → process
 * → domain, registration → domain); a missing entity yields the unresolved
 * context — the method body would 404 (or no-op delete) on it anyway, and the
 * aspect's documented ADR D5 posture covers the gap.
 */
@Configuration
public class AppGovernedResolvers {

    /** §4.3/§4.4 target vocabulary for app-core governance. */
    public static final String TARGET_PROCESS = "PROCESS";
    public static final String TARGET_PROCESS_STEP = "PROCESS_STEP";
    public static final String TARGET_READER_REGISTRATION = "READER_REGISTRATION";

    @Bean
    public GovernedContextResolver phaseStateGovernedResolver() {
        return GovernedContextResolver.of(PhaseStateService.class,
                (actionType, method, args) ->
                        args.length > 0 && args[0] instanceof UUID domainId
                                ? new GovernedContext(domainId, null, null)
                                : GovernedContext.unresolved());
    }

    @Bean
    public GovernedContextResolver processStepGovernedResolver(
            ProcessRepository processes, ProcessStepRepository steps) {
        return GovernedContextResolver.of(ProcessStepService.class,
                (actionType, method, args) -> switch (method.getName()) {
                    case "create" -> {
                        UUID processId = (UUID) args[0];
                        yield processes.findById(processId)
                                .map(process -> new GovernedContext(process.getDomainId(),
                                        new TargetRef(TARGET_PROCESS, processId.toString()), null))
                                .orElseGet(GovernedContext::unresolved);
                    }
                    case "update", "delete" -> {
                        UUID stepId = (UUID) args[0];
                        yield steps.findById(stepId)
                                .flatMap(step -> processes.findById(step.getProcessId()))
                                .map(process -> new GovernedContext(process.getDomainId(),
                                        new TargetRef(TARGET_PROCESS_STEP, stepId.toString()), null))
                                .orElseGet(GovernedContext::unresolved);
                    }
                    default -> GovernedContext.unresolved();
                });
    }

    @Bean
    public GovernedContextResolver readerRegistrationGovernedResolver(
            ReaderRegistrationRepository registrations) {
        return GovernedContextResolver.of(ReaderRegistrationService.class,
                (actionType, method, args) -> {
                    if ("create".equals(method.getName())
                            && args[0] instanceof ReaderRegistrationCreateRequest req) {
                        return new GovernedContext(req.domainId(), null, null);
                    }
                    // markRead / delete lead with the registration id.
                    if (!(args.length > 0 && args[0] instanceof UUID registrationId)) {
                        return GovernedContext.unresolved();
                    }
                    return registrations.findById(registrationId)
                            .map(r -> new GovernedContext(r.getDomainId(),
                                    new TargetRef(TARGET_READER_REGISTRATION,
                                            registrationId.toString()), null))
                            .orElseGet(GovernedContext::unresolved);
                });
    }
}
