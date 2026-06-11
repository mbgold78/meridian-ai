package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.ContractDeclineTrigger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Pre-agreed decline triggers (engine-spec contracts.md §3.2 table 5).
 * {@code armed}/{@code lastArmedAt} are written only by the sweep's arming
 * pass (§2.6).
 */
@Repository
public interface ContractDeclineTriggerRepository extends JpaRepository<ContractDeclineTrigger, UUID> {

    List<ContractDeclineTrigger> findByContractIdOrderBySequenceOrderAsc(UUID contractId);
}
