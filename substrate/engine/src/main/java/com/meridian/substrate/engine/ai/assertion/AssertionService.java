package com.meridian.substrate.engine.ai.assertion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.meridian.substrate.common.domain.Assertion;
import com.meridian.substrate.common.domain.enums.ProducedBy;
import com.meridian.substrate.common.repo.AssertionRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * CRUD-level operations on {@link Assertion}. The Assertion entity is not
 * directly user-facing — it is the substrate that AI mode services
 * (Generative / Validative / Assistive) commit through, so this service is
 * deliberately thin: build, save, fetch.
 *
 * <p><b>Port note:</b> homepoc carried a private {@code AssertionRepository}
 * inside the app AI package; substrate uses the shared
 * {@link com.meridian.substrate.common.repo.AssertionRepository} (chunk B1).
 */
@Service
public class AssertionService {

    private final AssertionRepository repository;
    private final ObjectMapper mapper;

    public AssertionService(AssertionRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    /**
     * Build and persist an Assertion. Evidence IDs are stored as a JSON array
     * of UUID strings; {@code producedByContext} is opaque JSON whose shape
     * varies by producer (mode + surface for AI, agent ID + scope for AGENT,
     * persona for HUMAN).
     */
    public Assertion record(UUID domainId,
                            String subject,
                            String predicate,
                            String object,
                            Float confidence,
                            List<UUID> evidenceIds,
                            ProducedBy producedBy,
                            JsonNode producedByContext) {
        Assertion a = new Assertion();
        a.setDomainId(domainId);
        a.setSubject(subject);
        a.setPredicate(predicate);
        a.setObject(object);
        a.setConfidence(confidence);
        a.setEvidenceIds(evidenceIdsToJson(evidenceIds));
        a.setProducedBy(producedBy);
        a.setProducedByContext(producedByContext);
        return repository.save(a);
    }

    public List<Assertion> findByDomain(UUID domainId) {
        return repository.findByDomainId(domainId);
    }

    public List<Assertion> findByDomainAndProducer(UUID domainId, ProducedBy producedBy) {
        return repository.findByDomainIdAndProducedBy(domainId, producedBy);
    }

    private JsonNode evidenceIdsToJson(List<UUID> evidenceIds) {
        ArrayNode array = mapper.createArrayNode();
        if (evidenceIds != null) {
            for (UUID id : evidenceIds) {
                array.add(id.toString());
            }
        }
        return array;
    }
}
