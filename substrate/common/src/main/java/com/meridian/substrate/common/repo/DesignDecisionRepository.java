package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.DesignDecision;
import com.meridian.substrate.common.domain.enums.DecisionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for the {@link DesignDecision} entity.
 */
@Repository
public interface DesignDecisionRepository extends JpaRepository<DesignDecision, UUID> {

    List<DesignDecision> findByDomainId(UUID domainId);

    List<DesignDecision> findByDomainIdAndStatus(UUID domainId, DecisionStatus status);
}
