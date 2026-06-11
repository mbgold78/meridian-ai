package com.meridian.substrate.engine.contract.registry;

import com.meridian.substrate.common.domain.enums.ContractActorType;

/**
 * Acting identity stamped on {@code contract_events} rows (engine-spec
 * contracts.md §3.2 table 13: {@code actor_type} + {@code actor_id}).
 * Pre-Phase-5 identity is advisory (spoofable persona/MCP headers, S9
 * equivalent — spec §15 extension point #12): recorded verbatim, hardened by
 * OIDC later with no schema change. {@code MONITOR}/{@code SYSTEM} are
 * engine-internal actors (§2.1).
 */
public record ContractActor(ContractActorType type, String id) {

    public ContractActor {
        if (type == null || id == null || id.isBlank()) {
            throw new IllegalArgumentException("contract actor requires a type and a non-blank id");
        }
    }

    public static ContractActor human(String id) {
        return new ContractActor(ContractActorType.HUMAN, id);
    }

    /** §8.3 governed MCP write lane — id is the (spoofable, verbatim) {@code X-MCP-Principal}. */
    public static ContractActor agent(String id) {
        return new ContractActor(ContractActorType.AGENT, id);
    }

    public static ContractActor system(String id) {
        return new ContractActor(ContractActorType.SYSTEM, id);
    }

    /** Engine-internal monitor actor (§2.1) — id is the monitor's name or id. */
    public static ContractActor monitor(String id) {
        return new ContractActor(ContractActorType.MONITOR, id);
    }
}
