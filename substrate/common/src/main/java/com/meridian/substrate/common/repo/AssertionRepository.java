package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.Assertion;
import com.meridian.substrate.common.domain.enums.ProducedBy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AssertionRepository extends JpaRepository<Assertion, UUID> {
    List<Assertion> findByDomainId(UUID domainId);
    List<Assertion> findByDomainIdAndProducedBy(UUID domainId, ProducedBy producedBy);
    List<Assertion> findBySubject(String subject);
}
