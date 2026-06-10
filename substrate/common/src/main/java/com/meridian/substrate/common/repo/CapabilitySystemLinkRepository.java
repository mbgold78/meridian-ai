package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.CapabilitySystemLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CapabilitySystemLinkRepository extends JpaRepository<CapabilitySystemLink, UUID> {

    List<CapabilitySystemLink> findByCapabilityId(UUID capabilityId);

    List<CapabilitySystemLink> findBySystemId(UUID systemId);
}
