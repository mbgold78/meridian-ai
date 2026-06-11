package com.meridian.substrate.engine.contract.gateway;

import com.meridian.substrate.common.domain.enums.PrincipalType;
import com.meridian.substrate.engine.contract.ContractProperties;
import com.meridian.substrate.engine.contract.gateway.GovernedContextResolver.GovernedContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The §4.2 PEP-2 enforcement aspect (engine-spec contracts.md §4.2/§9;
 * ADR-002 D2): every {@code @Governed} service method invocation builds a
 * {@link GovernedAction} and transits {@link ContractGateway#evaluate}
 * BEFORE the method body runs.
 *
 * <p><b>Transaction ordering (§4.2):</b> the aspect runs INSIDE the method's
 * transaction — {@link GovernedEnforcementConfig} pins the transaction
 * advisor at a higher precedence than {@link #GOVERNED_ASPECT_ORDER}, so an
 * enforce-mode DENY ({@link ContractDeniedException}) propagates through the
 * transaction interceptor and the business mutation rolls back automatically,
 * while the DENY decision row survives in REQUIRES_NEW (§10.4). Methods that
 * manage their own transactions ({@code TransactionTemplate} upserts, §10.5)
 * are evaluated before their first transaction begins — equally safe: nothing
 * has mutated yet.
 *
 * <p><b>Principal resolution order:</b> {@link GovernedPrincipalScope}
 * (explicit — the MCP write adapters' AGENT lane) →
 * {@link GovernedPrincipalSource} beans (the app module's persona-header
 * HUMAN lane) → {@code Principal.service()} (engine-internal callers:
 * DemoPack seeding, schedulers — SERVICE is fail-closed under ADR D5, the
 * conservative default).
 *
 * <p><b>Unresolvable context posture:</b> when the declaring service has no
 * {@link GovernedContextResolver} bean or the resolver cannot determine the
 * domain (e.g. the targeted entity does not exist — the method body would
 * 404 anyway), no {@link GovernedAction} can be built and no decision row can
 * be written ({@code domain_id} is NOT NULL). The aspect applies the ADR D5
 * shape without a row, ERROR-logged so it is never silent: HUMAN proceeds
 * (fail-open — humans are never locked out by an engine fault); AGENT/SERVICE
 * are denied in enforce mode and proceed in shadow/off (shadow never blocks,
 * ADR D4). Rowed D5 handling for failures DURING evaluation stays inside
 * {@link ContractGateway}.
 *
 * <p>The dispatch-result apply PEP (§4.2 PEP 3) is deliberately NOT this
 * aspect: {@code OrchestratorSuggestClient} builds its
 * {@code apply_dispatch_result} action explicitly, carrying the dispatch id.
 */
@Aspect
@Component
@Order(GovernedActionAspect.GOVERNED_ASPECT_ORDER)
public class GovernedActionAspect {

    /**
     * Advisor order: must be a LOWER precedence (higher value) than
     * {@link GovernedEnforcementConfig#TRANSACTION_ADVISOR_ORDER} so the
     * transaction interceptor wraps OUTSIDE this aspect (§4.2).
     */
    public static final int GOVERNED_ASPECT_ORDER = org.springframework.core.Ordered.LOWEST_PRECEDENCE;

    private static final Logger log = LoggerFactory.getLogger(GovernedActionAspect.class);

    private final ContractGateway gateway;
    private final ContractProperties properties;
    private final Map<Class<?>, GovernedContextResolver> resolversByService;
    private final List<GovernedPrincipalSource> principalSources;

    public GovernedActionAspect(ContractGateway gateway,
                                ContractProperties properties,
                                List<GovernedContextResolver> resolvers,
                                List<GovernedPrincipalSource> principalSources) {
        this.gateway = gateway;
        this.properties = properties;
        this.resolversByService = resolvers.stream().collect(Collectors.toMap(
                GovernedContextResolver::serviceType, Function.identity(),
                (a, b) -> {
                    throw new IllegalStateException("duplicate GovernedContextResolver for "
                            + a.serviceType() + " (explicit per-service beans, §4.2)");
                }));
        this.principalSources = List.copyOf(principalSources);
    }

    @Around("@annotation(governed)")
    public Object enforce(ProceedingJoinPoint pjp, Governed governed) throws Throwable {
        String actionType = governed.actionType();
        Principal principal = resolvePrincipal();
        GovernedContext context = resolveContext(pjp, actionType);

        if (context.domainId() == null) {
            return unresolvedPosture(pjp, actionType, principal);
        }
        // ContractDeniedException (enforce-mode DENY) propagates: the
        // transaction interceptor outside this aspect rolls the mutation back.
        gateway.evaluate(new GovernedAction(actionType, principal, context.domainId(),
                context.target(), context.payloadSummary()));
        return pjp.proceed();
    }

    // ------------------------------------------------------------- resolution

    private Principal resolvePrincipal() {
        Optional<Principal> scoped = GovernedPrincipalScope.current();
        if (scoped.isPresent()) {
            return scoped.get();
        }
        for (GovernedPrincipalSource source : principalSources) {
            Optional<Principal> principal = source.currentPrincipal();
            if (principal.isPresent()) {
                return principal.get();
            }
        }
        return Principal.service();
    }

    private GovernedContext resolveContext(ProceedingJoinPoint pjp, String actionType) {
        Class<?> serviceType = AopUtils.getTargetClass(pjp.getTarget());
        GovernedContextResolver resolver = lookupResolver(serviceType);
        if (resolver == null) {
            log.error("No GovernedContextResolver bean for governed service {} [action={}]"
                            + " — explicit per-service resolvers are required (§4.2);"
                            + " applying the unresolved-context posture",
                    serviceType.getName(), actionType);
            return GovernedContext.unresolved();
        }
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        try {
            GovernedContext context = resolver.resolve(actionType, method, pjp.getArgs());
            return context != null ? context : GovernedContext.unresolved();
        } catch (RuntimeException resolutionFailure) {
            // Resolver contract says "never throws" — backstop it anyway.
            log.error("GovernedContextResolver for {} threw [action={}] — applying the"
                            + " unresolved-context posture", serviceType.getName(), actionType,
                    resolutionFailure);
            return GovernedContext.unresolved();
        }
    }

    /** CGLIB target classes are the service classes themselves; walk up for safety. */
    private GovernedContextResolver lookupResolver(Class<?> serviceType) {
        for (Class<?> c = serviceType; c != null && c != Object.class; c = c.getSuperclass()) {
            GovernedContextResolver resolver = resolversByService.get(c);
            if (resolver != null) {
                return resolver;
            }
        }
        return null;
    }

    // ----------------------------------------------- unresolved-context posture

    /**
     * ADR D5 shape without a decision row (no domain ⇒ no row possible):
     * HUMAN proceeds, AGENT/SERVICE deny in enforce / proceed in shadow+off.
     * Always ERROR-logged — unresolvable governance is never silent.
     */
    private Object unresolvedPosture(ProceedingJoinPoint pjp, String actionType,
                                     Principal principal) throws Throwable {
        EnforcementMode mode = EnforcementMode.parse(properties.getEnforcement().getMode());
        log.error("Governed action '{}' has an unresolvable domain context"
                        + " [principal={}/{}, mode={}] — ADR D5 posture without a decision row"
                        + " (domain_id is NOT NULL; the gateway never saw this action)",
                actionType, principal.type(), principal.id(), mode.wire());
        if (principal.type() == PrincipalType.HUMAN || mode != EnforcementMode.ENFORCE) {
            return pjp.proceed();
        }
        throw new ContractDeniedException(
                new Decision.Deny(List.of(), List.of(),
                        ContractGateway.REASON_GATEWAY_ERROR, null),
                "Action '" + actionType + "' denied: governed context could not be resolved and"
                        + " principal type " + principal.type() + " is fail-closed (ADR D5)");
    }
}
