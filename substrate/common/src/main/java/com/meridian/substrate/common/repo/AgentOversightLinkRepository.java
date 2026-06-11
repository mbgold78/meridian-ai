package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.AgentOversightLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for the ordered oversight chain (engine-spec agents.md §2/§4
 * table 2). Full-replacement semantics live in the 6b oversight verb
 * (OVERSIGHT_CHANGED, before/after evented); deploy writes positions 1..N.
 */
@Repository
public interface AgentOversightLinkRepository extends JpaRepository<AgentOversightLink, UUID> {

    /** Rendered "A → B → C"; position 1 is the first escalation/notification target. */
    List<AgentOversightLink> findByAgentRegistrationIdOrderByPositionAsc(UUID agentRegistrationId);

    void deleteByAgentRegistrationId(UUID agentRegistrationId);
}
