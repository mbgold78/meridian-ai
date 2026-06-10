package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.ProcessStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ProcessStepRepository extends JpaRepository<ProcessStep, UUID> {

    List<ProcessStep> findByProcessIdOrderBySequenceOrderAsc(UUID processId);
}
