package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.SystemUpload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for the {@link SystemUpload} entity.
 */
@Repository
public interface SystemUploadRepository extends JpaRepository<SystemUpload, UUID> {

    List<SystemUpload> findBySystemId(UUID systemId);
}
