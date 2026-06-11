package com.meridian.substrate.engine.contract.gateway;

import java.util.Optional;

/**
 * Transport-side principal supplier for the {@link GovernedActionAspect}
 * (engine-spec contracts.md §4.2 PEP 2: "resolves principal from
 * {@code PersonaContext} (HUMAN)"). The engine module carries no spring-web
 * dependency and must not see the request-scoped persona holder — the app
 * module contributes a bean of this type that yields
 * {@code Principal(HUMAN, <persona identity>)} while a servlet request is
 * active and empty otherwise.
 *
 * <p>Resolution order in the aspect: {@link GovernedPrincipalScope} (explicit
 * — MCP AGENT / dispatch SERVICE) → sources in bean order → the documented
 * SERVICE fallback ({@code substrate-svc}) for engine-internal callers
 * (DemoPack seeding, schedulers). SERVICE is the conservative default: it is
 * fail-closed under the ADR D5 posture.
 */
public interface GovernedPrincipalSource {

    /** The current transport principal, or empty when this source has no caller context. */
    Optional<Principal> currentPrincipal();
}
