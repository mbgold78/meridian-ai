package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.ProcessRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for the {@link ProcessRole} entity.
 */
@Repository
public interface ProcessRoleRepository extends JpaRepository<ProcessRole, UUID> {

    List<ProcessRole> findByProcessId(UUID processId);

    List<ProcessRole> findByMemberId(UUID memberId);
}
