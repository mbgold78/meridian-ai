package com.meridian.substrate.engine.contract.gateway;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares one mutating service method a Policy Enforcement Point of the
 * contract gateway (engine-spec contracts.md §4.2 PEP 2; ADR-002 D2):
 * {@link GovernedActionAspect} builds a {@link GovernedAction} for every
 * invocation — principal from {@link GovernedPrincipalScope} /
 * {@link GovernedPrincipalSource}, domain/target/payload from the declaring
 * service's explicit {@link GovernedContextResolver} bean — and calls
 * {@link ContractGateway#evaluate} BEFORE the method body runs, inside the
 * method's transaction (the aspect is ordered after the transaction
 * interceptor, {@link GovernedEnforcementConfig}), so an enforce-mode DENY
 * throws {@link ContractDeniedException} and the business mutation rolls
 * back automatically while the DENY decision row survives in REQUIRES_NEW
 * (§10.4).
 *
 * <p>{@code actionType} is the guard-matching currency
 * ({@code contract_clause_guards.action_type}) and is shared across
 * transports: the REST lane (HUMAN persona principals) and the §8.3 MCP
 * write-tool lane (AGENT principals via {@link GovernedPrincipalScope})
 * evaluate the SAME action type for the same verb — guards govern actions,
 * not transports.
 *
 * <p>The §14.6 ArchUnit governed-coverage rule requires every public
 * {@code @Transactional(readOnly=false)} method in the governed packages to
 * carry this annotation or an auditable {@link Ungoverned} exemption —
 * "no route around" is CI-proven, not hoped (ADR D2).
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Governed {

    /** Governed action name, e.g. {@code contract.attest_commitment}, {@code cascade.issue}. */
    String actionType();
}
