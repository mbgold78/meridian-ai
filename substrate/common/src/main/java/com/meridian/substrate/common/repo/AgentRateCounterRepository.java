package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.AgentRateCounter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the DB-backed {@link AgentRateCounter} windows (engine-spec
 * agents.md §2/§4 table 9 / §5.5; ADR-003 D12 — the S12 lesson: never
 * in-memory atomics).
 *
 * <p>6c usage pattern: {@link #incrementWindow} first; zero rows updated ⇒
 * insert-on-absent; a racing insert losing on {@code uq_arc_window} is
 * retried AS an increment (spec §5.5 "insert-on-absent with unique-violation
 * retry-as-increment").
 */
@Repository
public interface AgentRateCounterRepository extends JpaRepository<AgentRateCounter, UUID> {

    Optional<AgentRateCounter> findByAgentRegistrationIdAndWindowStart(UUID agentRegistrationId,
                                                                       Instant windowStart);

    /**
     * Atomic in-database increment inside the gateway check transaction —
     * restart-safe and multi-instance-safe, unlike CostTracker's in-memory
     * atomics (S12). Returns the number of rows updated (0 = window row
     * absent; caller inserts then retries as increment on a unique violation).
     */
    @Modifying
    @Query("update AgentRateCounter c set c.actionCount = c.actionCount + 1 "
            + "where c.agentRegistrationId = :agentId and c.windowStart = :windowStart")
    int incrementWindow(@Param("agentId") UUID agentRegistrationId,
                        @Param("windowStart") Instant windowStart);
}
