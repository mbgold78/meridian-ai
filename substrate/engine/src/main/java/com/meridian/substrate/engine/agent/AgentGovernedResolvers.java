package com.meridian.substrate.engine.agent;

import com.meridian.substrate.common.repo.AgentRegistrationRepository;
import com.meridian.substrate.engine.contract.gateway.GovernedContextResolver;
import com.meridian.substrate.engine.contract.gateway.GovernedContextResolver.GovernedContext;
import com.meridian.substrate.engine.contract.gateway.TargetRef;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/**
 * The agent engine's explicit per-service {@code GovernedContextResolver}
 * beans (engine-spec contracts.md §4.2 PEP 2; agents.md §5.4): the deploy
 * verb transits {@code ContractGateway} as {@code actionType=agent.deploy}
 * with a HUMAN principal — a contract can guard agent deployment. The 6b
 * verbs join this resolver on the same chassis (registration-id-leading
 * signatures).
 */
@Configuration
public class AgentGovernedResolvers {

    /** §4.3/§4.4 target vocabulary for agent governance (matches the 3c guard seeding). */
    public static final String TARGET_AGENT_REGISTRATION = "AGENT_REGISTRATION";

    @Bean
    public GovernedContextResolver agentLifecycleGovernedResolver() {
        return GovernedContextResolver.of(AgentLifecycleService.class,
                (actionType, method, args) -> {
                    if (args.length > 0 && args[0] instanceof AgentLifecycleService.DeployCommand cmd) {
                        return new GovernedContext(cmd.domainId(),
                                new TargetRef(TARGET_AGENT_REGISTRATION, cmd.agentKey()), null);
                    }
                    return GovernedContext.unresolved();
                });
    }

    /**
     * §5.1 M4 — {@code agent.promote} leads with the registration id (the
     * cascade id-leading idiom); resolution may read the DB inside the
     * governed transaction. The contracts 3c acceptance's seeded
     * {@code agent.promote} DENY guard matches this context.
     */
    @Bean
    public GovernedContextResolver agentPromotionGovernedResolver(
            AgentRegistrationRepository registrations) {
        return GovernedContextResolver.of(AgentPromotionService.class,
                registrationIdLeading(registrations));
    }

    /** §12 {@code agent.heartbeat} (6c MCP agent-tool engine half). */
    @Bean
    public GovernedContextResolver agentLivenessGovernedResolver(
            AgentRegistrationRepository registrations) {
        return GovernedContextResolver.of(AgentLivenessService.class,
                registrationIdLeading(registrations));
    }

    /** §12 {@code agent.report_activity} (6c MCP agent-tool engine half). */
    @Bean
    public GovernedContextResolver agentMetricGovernedResolver(
            AgentRegistrationRepository registrations) {
        return GovernedContextResolver.of(AgentMetricService.class,
                registrationIdLeading(registrations));
    }

    /**
     * §12/§5.1 M17 {@code agent.acknowledge_control} — the ONE governed
     * write a suspended principal retains (§8.3 check-1 exception lives in
     * the registry gate, keyed on this action type).
     */
    @Bean
    public GovernedContextResolver agentControlConfirmationGovernedResolver(
            AgentRegistrationRepository registrations) {
        return GovernedContextResolver.of(AgentControlConfirmationService.class,
                registrationIdLeading(registrations));
    }

    /**
     * The shared registration-id-leading resolution (cascade id-leading
     * idiom) — via the SCALAR projection, never the entity: the §8 registry
     * gate commits registration updates in REQUIRES_NEW between resolution
     * and the method body, and a pre-gate L1-cached entity would flush a
     * stale {@code @Version} (see {@code findGovernedRefById}).
     */
    private static GovernedContextResolver.Resolution registrationIdLeading(
            AgentRegistrationRepository registrations) {
        return (actionType, method, args) -> {
            if (!(args.length > 0 && args[0] instanceof UUID registrationId)) {
                return GovernedContext.unresolved();
            }
            return registrations.findGovernedRefById(registrationId)
                    .map(ref -> new GovernedContext(ref.getDomainId(),
                            new TargetRef(TARGET_AGENT_REGISTRATION, ref.getAgentKey()),
                            null))
                    .orElseGet(GovernedContext::unresolved);
        };
    }
}
