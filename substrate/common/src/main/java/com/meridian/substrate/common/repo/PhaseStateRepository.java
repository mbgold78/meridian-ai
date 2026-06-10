package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.PhaseState;
import com.meridian.substrate.common.domain.enums.LifecyclePhase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PhaseStateRepository extends JpaRepository<PhaseState, UUID> {

    List<PhaseState> findByDomainId(UUID domainId);

    Optional<PhaseState> findByDomainIdAndPhase(UUID domainId, LifecyclePhase phase);
}
