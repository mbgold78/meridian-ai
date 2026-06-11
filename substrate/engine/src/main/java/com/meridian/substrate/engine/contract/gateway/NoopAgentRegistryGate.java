package com.meridian.substrate.engine.contract.gateway;

import org.springframework.stereotype.Component;

/**
 * The default {@link AgentRegistryGate} bean: always allow-unflagged
 * (engine-spec agents.md §8.1; ADR-003 D7). This is the degraded
 * whole-or-in-part posture made a BEAN, not a conditional: a contracts
 * deployment without the agents module evaluates AGENT principals against
 * clause guards exactly as before 6c, and the agent engine's control-effect
 * derivation reads {@link #enforcesRegistry()} {@code == false} as "no
 * gateway enforcement exists — disclose RECORDED_INTENT" (invariant I8).
 *
 * <p>{@code engine.agent} contributes {@code AgentRegistryGateImpl} as
 * {@code @Primary}; when both are on the classpath the real gate wins
 * injection and this bean is inert.
 */
@Component
public class NoopAgentRegistryGate implements AgentRegistryGate {

    @Override
    public boolean enforcesRegistry() {
        return false;
    }

    @Override
    public Verdict evaluate(GovernedAction action, EnforcementMode mode) {
        return Verdict.Allow.clean();
    }
}
