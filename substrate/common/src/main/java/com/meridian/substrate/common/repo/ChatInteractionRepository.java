package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.ChatInteraction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for the {@link ChatInteraction} entity.
 */
@Repository
public interface ChatInteractionRepository extends JpaRepository<ChatInteraction, UUID> {

    List<ChatInteraction> findByDomainIdOrderByCreatedAtDesc(UUID domainId);
}
