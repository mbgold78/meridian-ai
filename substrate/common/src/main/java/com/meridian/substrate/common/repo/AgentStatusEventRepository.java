package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.AgentStatusEvent;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Insert-only repository surface for the APPEND-ONLY
 * {@link AgentStatusEvent} audit log (engine-spec agents.md §4/§5.5;
 * non-negotiable #3 / invariant I4; ADR-003 D1).
 *
 * <p>Deliberately extends {@link Repository} — NOT {@code JpaRepository} — so
 * no update/delete methods exist anywhere in the type system (cascade §4
 * idiom; pinned by the §14.6-style ArchUnit append-only rules). {@code save}
 * is used for initial inserts only; services never mutate loaded event rows
 * (the 6b chain mirror writes {@code decision_log_hash} via its own governed
 * lane), and the Postgres V6_1 trigger raises on any UPDATE/DELETE as the
 * DB-level backstop (H2 dev is service-enforced only, precedent V2_1/V4_1).
 */
@org.springframework.stereotype.Repository
public interface AgentStatusEventRepository extends Repository<AgentStatusEvent, UUID> {

    /** Insert-only in practice (append-only log) — never call with a loaded, mutated row. */
    AgentStatusEvent save(AgentStatusEvent e);

    Optional<AgentStatusEvent> findById(UUID id);

    /** The audit timeline, event_seq asc (spec §7.1 {@code /events}). */
    List<AgentStatusEvent> findByAgentRegistrationIdOrderByEventSeqAsc(UUID agentRegistrationId);

    /** Idempotency-replay lookup under the registration lock (cascade D8 idiom). */
    Optional<AgentStatusEvent> findByAgentRegistrationIdAndIdempotencyKey(UUID agentRegistrationId, String key);

    /** Latest event — derived {@code recentActivity} fallback + rebuilder watermark (I10). */
    Optional<AgentStatusEvent> findFirstByAgentRegistrationIdOrderByEventSeqDesc(UUID agentRegistrationId);

    /** Detail {@code recentEvents[5]} (spec §7.3). */
    List<AgentStatusEvent> findTop5ByAgentRegistrationIdOrderByEventSeqDesc(UUID agentRegistrationId);

    /**
     * Latest event of a type — the escalation sweeper resolves the triggering
     * control event for its {@code unconfirmed:{agentId}:{eventId}:{n}}
     * dedupe key (spec §13.4).
     */
    Optional<AgentStatusEvent> findFirstByAgentRegistrationIdAndEventTypeOrderByEventSeqDesc(
            UUID agentRegistrationId, com.meridian.substrate.common.domain.enums.AgentEventType eventType);

    /** Cross-fleet recent-events lane for {@code GET /agents/feed} (idx_ase_domain; spec §7.1). */
    List<AgentStatusEvent> findTop50ByDomainIdOrderByOccurredAtDescEventSeqDesc(UUID domainId);

    long countByAgentRegistrationId(UUID agentRegistrationId);
}
