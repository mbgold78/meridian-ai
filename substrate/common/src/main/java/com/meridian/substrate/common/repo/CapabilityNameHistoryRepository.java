package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.CapabilityNameHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CapabilityNameHistoryRepository extends JpaRepository<CapabilityNameHistory, UUID> {

    List<CapabilityNameHistory> findByCapabilityIdOrderByCreatedAtAsc(UUID capabilityId);
}
