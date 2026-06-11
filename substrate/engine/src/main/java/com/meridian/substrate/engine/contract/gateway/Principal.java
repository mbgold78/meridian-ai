package com.meridian.substrate.engine.contract.gateway;

import com.meridian.substrate.common.domain.enums.PrincipalType;

/**
 * The acting principal behind a {@link GovernedAction} (engine-spec
 * contracts.md §4.1): HUMAN = persona id (S9 caveat — spoofable header,
 * recorded verbatim, spec §15 #12), AGENT = MCP principal
 * ({@code X-MCP-Principal}, §8.1), SERVICE = {@code substrate-svc} (the
 * dispatch-result apply path, §4.2 PEP 3).
 *
 * <p>The fail posture is principal-typed (ADR D5): gateway evaluation failure
 * ⇒ deny path for AGENT/SERVICE (fail-closed), ALLOW_WITH_FLAG for HUMAN
 * (fail-open — humans are never locked out of their system of record by an
 * engine fault).
 */
public record Principal(PrincipalType type, String id) {

    /** §4.2 PEP 3 service identity. */
    public static final String SUBSTRATE_SERVICE_ID = "substrate-svc";

    public Principal {
        if (type == null || id == null || id.isBlank()) {
            throw new IllegalArgumentException("principal requires a type and a non-blank id");
        }
    }

    public static Principal human(String personaId) {
        return new Principal(PrincipalType.HUMAN, personaId);
    }

    public static Principal agent(String mcpPrincipalId) {
        return new Principal(PrincipalType.AGENT, mcpPrincipalId);
    }

    public static Principal service() {
        return new Principal(PrincipalType.SERVICE, SUBSTRATE_SERVICE_ID);
    }
}
