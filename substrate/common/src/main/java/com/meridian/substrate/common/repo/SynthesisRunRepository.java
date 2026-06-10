package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.SynthesisRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for the {@link SynthesisRun} entity.
 */
@Repository
public interface SynthesisRunRepository extends JpaRepository<SynthesisRun, UUID> {

    List<SynthesisRun> findByDomainId(UUID domainId);
}
