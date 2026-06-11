package com.meridian.substrate.engine.contract.gateway;

import com.meridian.substrate.common.repo.ContractClauseGuardRepository;
import com.meridian.substrate.engine.contract.events.ContractActivatedEvent;
import com.meridian.substrate.engine.contract.events.ContractDeclinedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-domain active-guard cache on the PDP hot path (engine-spec contracts.md
 * §4.3; ADR D9): in-memory map keyed {@code (domainId)} → guard snapshot,
 * loaded with ONE indexed read on miss, so evaluation stays at 1–2 indexed
 * reads + in-memory predicate eval (target p50 &lt; 5 ms).
 *
 * <p>Invalidation (§10.8): an in-process listener on contract
 * activation/decline events (both flip {@code active} on every guard of the
 * contract, §2.2) + a 30 s TTL safety net. Guard-mutation events do not exist
 * yet (authoring creates guards {@code active=false}, which never enter the
 * active set; the first guard-editing surface must publish an invalidation
 * event — §9). SINGLE-NODE ASSUMPTION documented in §10.8: multi-node needs a
 * bus; out of scope, the TTL bounds staleness meanwhile. Events are published
 * after commit, so a snapshot loaded mid-transition is stale at most one TTL.
 */
@Component
public class GuardCache {

    /** §4.3 TTL safety net. */
    static final Duration TTL = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(GuardCache.class);

    private final ContractClauseGuardRepository guardRepository;
    private final Clock clock;
    private final Map<UUID, Snapshot> byDomain = new ConcurrentHashMap<>();

    private record Snapshot(List<GuardView> guards, Instant loadedAt) {
    }

    public GuardCache(ContractClauseGuardRepository guardRepository, Clock clock) {
        this.guardRepository = guardRepository;
        this.clock = clock;
    }

    /**
     * The §4.3 entry point: active guards of {@code domainId} for
     * {@code actionType} (entity-type narrowing happens in the gateway — it
     * owns the target). Storage is per-domain per spec; the action-type
     * filter is in-memory over the snapshot.
     */
    public List<GuardView> activeGuards(UUID domainId, String actionType) {
        Instant now = clock.instant();
        Snapshot snapshot = byDomain.compute(domainId, (key, existing) ->
                existing != null && !isExpired(existing, now) ? existing : load(key, now));
        return snapshot.guards().stream()
                .filter(g -> g.actionType().equals(actionType))
                .toList();
    }

    /** Drops one domain's snapshot; next evaluation reloads. */
    public void invalidate(UUID domainId) {
        byDomain.remove(domainId);
    }

    // ------------------------------------------------------------- listeners

    /** Activation flips guards live (§2.2) — published after commit. */
    @EventListener
    public void onContractActivated(ContractActivatedEvent event) {
        invalidate(event.domainId());
        log.debug("Guard cache invalidated [domainId={}] on activation of '{}'",
                event.domainId(), event.contractKey());
    }

    /** Decline deactivates all guards atomically (§2.2) — published after commit. */
    @EventListener
    public void onContractDeclined(ContractDeclinedEvent event) {
        invalidate(event.domainId());
        log.debug("Guard cache invalidated [domainId={}] on decline of '{}'",
                event.domainId(), event.contractKey());
    }

    // --------------------------------------------------------------- loading

    private Snapshot load(UUID domainId, Instant now) {
        List<GuardView> guards = guardRepository.findActiveByDomainId(domainId).stream()
                .map(GuardView::of)
                .toList();
        return new Snapshot(guards, now);
    }

    private static boolean isExpired(Snapshot snapshot, Instant now) {
        return now.isAfter(snapshot.loadedAt().plus(TTL));
    }
}
