package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.ReaderRegistration;
import com.meridian.substrate.common.domain.enums.ReaderKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReaderRegistrationRepository extends JpaRepository<ReaderRegistration, UUID> {

    List<ReaderRegistration> findByDomainId(UUID domainId);

    List<ReaderRegistration> findByDomainIdAndKind(UUID domainId, ReaderKind kind);
}
