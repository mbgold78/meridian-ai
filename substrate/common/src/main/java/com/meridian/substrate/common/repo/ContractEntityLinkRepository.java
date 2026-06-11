package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.ContractEntityLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Contract → entity navigation back-refs (engine-spec contracts.md §3.2
 * table 6; fixture {@code linkedSubsidiarySlugs} seam).
 */
@Repository
public interface ContractEntityLinkRepository extends JpaRepository<ContractEntityLink, UUID> {

    List<ContractEntityLink> findByContractId(UUID contractId);
}
