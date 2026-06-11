package com.meridian.substrate.engine.contract.gateway;

import com.fasterxml.jackson.databind.JsonNode;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Explicit per-service domain/target/payload resolution for {@code @Governed}
 * methods (engine-spec contracts.md §4.2 PEP 2: "domainId/target from method
 * args via a {@code GovernedContextResolver} — explicit per-service resolver
 * beans; no SpEL magic"). One bean per governed service class, registered by
 * the service's own module ({@code ContractGovernedResolvers},
 * {@code CascadeGovernedResolvers}, the app module's
 * {@code AppGovernedResolvers}); {@link GovernedActionAspect} indexes them by
 * {@link #serviceType()}.
 *
 * <p>Resolution is allowed to read the database (e.g. breachId → contract →
 * domainId) — it runs inside the governed method's transaction. A resolver
 * that cannot determine the domain (entity not found, unknown method) returns
 * a context with a null {@code domainId}; the aspect then applies the ADR D5
 * posture WITHOUT a decision row (the {@code domain_id} column is NOT NULL —
 * see {@link GovernedActionAspect} for the loud-logging contract).
 */
public interface GovernedContextResolver {

    /**
     * Domain/target/payload of one governed invocation (§4.1 fields minus
     * principal/actionType, which the aspect owns). {@code payloadSummary} is
     * SMALL — exactly the fields guard predicates may reference via
     * {@code payload.*} dot-paths (§4.4); when a resolver contributes none,
     * predicate-bearing guards evaluate INDETERMINATE and are flagged, never
     * silent (§4.4).
     */
    record GovernedContext(UUID domainId, TargetRef target, JsonNode payloadSummary) {

        public static GovernedContext unresolved() {
            return new GovernedContext(null, null, null);
        }
    }

    /** The governed service class this resolver is the explicit bean for. */
    Class<?> serviceType();

    /** Resolves one invocation; never throws — return {@link GovernedContext#unresolved()} instead. */
    GovernedContext resolve(String actionType, Method method, Object[] args);

    /** Functional form for the per-service resolver bean methods. */
    @FunctionalInterface
    interface Resolution {
        GovernedContext resolve(String actionType, Method method, Object[] args);
    }

    /** Builds an explicit per-service resolver from a resolution function. */
    static GovernedContextResolver of(Class<?> serviceType, Resolution resolution) {
        return new GovernedContextResolver() {
            @Override
            public Class<?> serviceType() {
                return serviceType;
            }

            @Override
            public GovernedContext resolve(String actionType, Method method, Object[] args) {
                return resolution.resolve(actionType, method, args);
            }

            @Override
            public String toString() {
                return "GovernedContextResolver[" + serviceType.getSimpleName() + "]";
            }
        };
    }
}
