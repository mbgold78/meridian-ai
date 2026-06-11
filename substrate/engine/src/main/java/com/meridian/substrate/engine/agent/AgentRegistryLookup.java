package com.meridian.substrate.engine.agent;

import com.meridian.substrate.common.domain.AgentThrottleBudget;
import com.meridian.substrate.common.domain.enums.AgentOperationalMode;
import com.meridian.substrate.common.repo.AgentRegistrationRepository;
import com.meridian.substrate.common.repo.AgentThrottleBudgetRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry-state read cache on the gateway hot path (engine-spec agents.md
 * §8.3; ADR-003 D7): principal → immutable snapshot of exactly the columns
 * the gate checks, "cached beside {@code GuardCache}" — same 30 s TTL safety
 * net, invalidated by every lifecycle verb that changes gate-relevant state
 * (the §5.3 GATEWAY_BLOCK disclosure's "cache invalidated, 30s TTL net").
 * Negative results (unregistered principals) are cached under the same TTL
 * so an unknown agent knocking repeatedly stays one indexed read per window.
 *
 * <p>The contracts p50 &lt; 5 ms budget now covers both lookups — the
 * Testcontainers gateway suite carries the perf assertion (§8.3).
 * SINGLE-NODE ASSUMPTION inherited from {@code GuardCache} (§10.8): the TTL
 * bounds multi-node staleness; a bus is out of scope.
 *
 * <p>Deliberately a SNAPSHOT cache, never detached JPA entities: the
 * {@code AgentRegistryGateImpl} checks run against plain values; the current
 * rate-counter window is NOT cached (DB-backed atomic increments are the
 * whole point — ADR-003 D12, the S12 lesson).
 */
@Component
public class AgentRegistryLookup {

    /** §8.3 TTL safety net — same constant discipline as {@code GuardCache}. */
    static final Duration TTL = Duration.ofSeconds(30);

    /** §3 budget-kind with a 6c evaluator (the rest are EXTENSION POINT(agent-10)). */
    public static final String BUDGET_ACTIONS_PER_WINDOW = "ACTIONS_PER_WINDOW";

    private final AgentRegistrationRepository registrationRepository;
    private final AgentThrottleBudgetRepository throttleBudgetRepository;
    private final Clock clock;
    private final Map<String, Entry> byPrincipal = new ConcurrentHashMap<>();

    public AgentRegistryLookup(AgentRegistrationRepository registrationRepository,
                               AgentThrottleBudgetRepository throttleBudgetRepository,
                               Clock clock) {
        this.registrationRepository = registrationRepository;
        this.throttleBudgetRepository = throttleBudgetRepository;
        this.clock = clock;
    }

    /** Exactly the gate-relevant registry state (§8.3 checks 1–5). */
    public record Snapshot(UUID registrationId, String agentKey, UUID domainId,
                           AgentOperationalMode mode, boolean readOnly,
                           int openInterventionCount, ActionsBudget actionsBudget) {
    }

    /** The active ACTIONS_PER_WINDOW budget definition, when one exists. */
    public record ActionsBudget(Double limitValue, Integer windowMinutes) {
    }

    private record Entry(Optional<Snapshot> snapshot, Instant loadedAt) {
    }

    /** One indexed read on miss ({@code idx_agents_principal}); TTL-bounded. */
    public Optional<Snapshot> resolve(String mcpPrincipalId) {
        Instant now = clock.instant();
        Entry entry = byPrincipal.compute(mcpPrincipalId, (key, existing) ->
                existing != null && !isExpired(existing, now) ? existing : load(key, now));
        return entry.snapshot();
    }

    /**
     * Lifecycle-verb invalidation (pause/resume/kill/retire/throttle/
     * unthrottle/promote/bind/revoke + engine-raised intervention flips):
     * drops every cached entry referencing the registration — including the
     * negative entry a just-bound principal may have left behind.
     */
    public void invalidate(UUID agentRegistrationId) {
        byPrincipal.values().removeIf(entry -> entry.snapshot()
                .map(s -> s.registrationId().equals(agentRegistrationId))
                .orElse(false));
    }

    /** M18 bind/revoke: the principal key itself changes hands. */
    public void invalidatePrincipal(String mcpPrincipalId) {
        if (mcpPrincipalId != null) {
            byPrincipal.remove(mcpPrincipalId);
        }
    }

    // ----------------------------------------------------------------- loading

    private Entry load(String mcpPrincipalId, Instant now) {
        // SCALAR projection, deliberately: the lookup runs inside the
        // governed business transaction BEFORE the gate's REQUIRES_NEW
        // registration writes — parking the ENTITY in the caller's
        // persistence context here would flush a stale @Version after the
        // gate commits (see AgentRegistrationRepository.GateView).
        Optional<Snapshot> snapshot = registrationRepository
                .findGateViewByMcpPrincipalId(mcpPrincipalId)
                .map(this::snapshotOf);
        return new Entry(snapshot, now);
    }

    private Snapshot snapshotOf(AgentRegistrationRepository.GateView view) {
        ActionsBudget budget = throttleBudgetRepository
                .findByAgentRegistrationIdAndBudgetKindAndActiveTrue(
                        view.getId(), BUDGET_ACTIONS_PER_WINDOW)
                .map(this::budgetOf)
                .orElse(null);
        return new Snapshot(view.getId(), view.getAgentKey(), view.getDomainId(),
                view.getOperationalMode(), Boolean.TRUE.equals(view.getReadOnly()),
                view.getOpenInterventionCount() == null ? 0 : view.getOpenInterventionCount(),
                budget);
    }

    private ActionsBudget budgetOf(AgentThrottleBudget budget) {
        return new ActionsBudget(budget.getLimitValue(), budget.getWindowMinutes());
    }

    private static boolean isExpired(Entry entry, Instant now) {
        return now.isAfter(entry.loadedAt().plus(TTL));
    }
}
