package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.CapabilityProcessLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CapabilityProcessLinkRepository extends JpaRepository<CapabilityProcessLink, UUID> {

    List<CapabilityProcessLink> findByCapabilityId(UUID capabilityId);

    List<CapabilityProcessLink> findByProcessId(UUID processId);
}
