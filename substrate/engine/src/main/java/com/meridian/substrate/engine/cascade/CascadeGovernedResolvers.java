package com.meridian.substrate.engine.cascade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.repo.CascadeDraftRepository;
import com.meridian.substrate.common.repo.CascadeRepository;
import com.meridian.substrate.engine.contract.gateway.GovernedContextResolver;
import com.meridian.substrate.engine.contract.gateway.GovernedContextResolver.GovernedContext;
import com.meridian.substrate.engine.contract.gateway.TargetRef;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/**
 * The cascade engine's explicit per-service {@code GovernedContextResolver}
 * beans (engine-spec contracts.md §4.2 PEP 2 — the Phase-2 cascade engine's
 * mutating services are governed REST service commands under ADR-002 D2).
 * Domain resolution walks the same entity the verb locks (draft / cascade);
 * a missing entity yields {@link GovernedContext#unresolved()} — the method
 * body would 404 on it anyway.
 */
@Configuration
public class CascadeGovernedResolvers {

    /** §4.3/§4.4 target vocabulary for cascade governance. */
    public static final String TARGET_CASCADE = "CASCADE";
    public static final String TARGET_CASCADE_DRAFT = "CASCADE_DRAFT";

    @Bean
    public GovernedContextResolver cascadeDraftGovernedResolver(
            CascadeDraftRepository drafts) {
        return GovernedContextResolver.of(CascadeDraftService.class,
                (actionType, method, args) -> {
                    if ("create".equals(method.getName())) {
                        var cmd = (CascadeDraftService.CreateCommand) args[0];
                        return new GovernedContext(cmd.domainId(), null, null);
                    }
                    // patch / discard / suggest / issue all lead with the draft id.
                    if (!(args.length > 0 && args[0] instanceof UUID draftId)) {
                        return GovernedContext.unresolved();
                    }
                    return drafts.findById(draftId)
                            .map(draft -> new GovernedContext(draft.getDomainId(),
                                    new TargetRef(TARGET_CASCADE_DRAFT, draftId.toString()), null))
                            .orElseGet(GovernedContext::unresolved);
                });
    }

    @Bean
    public GovernedContextResolver cascadeLifecycleGovernedResolver(
            CascadeRepository cascades, ObjectMapper objectMapper) {
        return GovernedContextResolver.of(CascadeLifecycleService.class,
                (actionType, method, args) -> {
                    // cascade / absorb / decline / recascade / resolve lead with the cascade id.
                    if (!(args.length > 0 && args[0] instanceof UUID cascadeId)) {
                        return GovernedContext.unresolved();
                    }
                    ObjectNode payload = null;
                    if (args.length > 1 && args[1] instanceof Integer depth) {
                        payload = objectMapper.createObjectNode();
                        payload.put("depth", depth);
                    }
                    final ObjectNode payloadSummary = payload;
                    return cascades.findById(cascadeId)
                            .map(cascade -> new GovernedContext(cascade.getDomainId(),
                                    new TargetRef(TARGET_CASCADE, cascadeId.toString()),
                                    payloadSummary))
                            .orElseGet(GovernedContext::unresolved);
                });
    }
}
