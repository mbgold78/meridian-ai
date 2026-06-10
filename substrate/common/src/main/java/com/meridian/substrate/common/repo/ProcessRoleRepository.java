package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.ProcessRole;
import com.meridian.substrate.common.domain.enums.Participation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for the {@link ProcessRole} entity.
 */
@Repository
public interface ProcessRoleRepository extends JpaRepository<ProcessRole, UUID> {

    List<ProcessRole> findByProcessId(UUID processId);

    List<ProcessRole> findByMemberId(UUID memberId);

    /** Natural-key lookup for the ingestion idempotent upsert (port-map §2.4 C6). */
    Optional<ProcessRole> findFirstByProcessIdAndMemberIdAndParticipation(
            UUID processId, UUID memberId, Participation participation);
}
