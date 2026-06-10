package com.meridian.substrate.app.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.CanonicalEntity;
import com.meridian.substrate.common.domain.EntityOverlap;
import com.meridian.substrate.common.domain.enums.ExecutionMode;
import com.meridian.substrate.common.repo.CanonicalEntityRepository;
import com.meridian.substrate.common.repo.EntityOverlapRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Seeds canonical entities and the overlap relationships between them.
 * Extracted from SystemSeeder because canonical entities reference both
 * systems AND processes — keeping them with systems would create a cycle
 * with ProcessSeeder (which needs systems for process-system links).
 */
@Component
public class CanonicalEntitySeeder implements Seeder {

    private static final Logger log = LoggerFactory.getLogger(CanonicalEntitySeeder.class);

    private final ObjectMapper objectMapper;
    private final CanonicalEntityRepository canonicalEntityRepository;
    private final EntityOverlapRepository entityOverlapRepository;

    public CanonicalEntitySeeder(ObjectMapper objectMapper,
                                  CanonicalEntityRepository canonicalEntityRepository,
                                  EntityOverlapRepository entityOverlapRepository) {
        this.objectMapper = objectMapper;
        this.canonicalEntityRepository = canonicalEntityRepository;
        this.entityOverlapRepository = entityOverlapRepository;
    }

    @Override
    public void seedBulk(SeedContext ctx) {
        UUID domainId = ctx.getDomainId();
        try {
            int entities = seedEntities(domainId, ctx);
            int overlaps = seedOverlaps(domainId, ctx);
            log.info("[seed] CanonicalEntitySeeder: {} entities, {} overlaps", entities, overlaps);
        } catch (IOException e) {
            throw new RuntimeException("CanonicalEntitySeeder failed", e);
        }
    }

    private int seedEntities(UUID domainId, SeedContext ctx) throws IOException {
        List<JsonNode> nodes = SeedFiles.load(objectMapper, "demopack/canonical-entities.json");
        for (JsonNode n : nodes) {
            CanonicalEntity ce = new CanonicalEntity();
            ce.setDomainId(domainId);
            ce.setName(n.get("name").asText());
            ce.setEntityType(n.get("entityType").asText());
            ce.setDescription(SeedFiles.textOrNull(n, "description"));
            if (n.has("executionMode") && !n.get("executionMode").isNull()) {
                ce.setExecutionMode(ExecutionMode.valueOf(n.get("executionMode").asText()));
            }
            if (n.has("systemName") && !n.get("systemName").isNull()) {
                UUID systemId = ctx.systemsByName().get(n.get("systemName").asText());
                if (systemId != null) {
                    ce.setSystemId(systemId);
                } else {
                    log.warn("System not found for canonical entity '{}': {}",
                            ce.getName(), n.get("systemName").asText());
                }
            }
            if (n.has("processName") && !n.get("processName").isNull()) {
                UUID processId = ctx.processesByName().get(n.get("processName").asText());
                if (processId != null) {
                    ce.setProcessId(processId);
                } else {
                    log.warn("Process not found for canonical entity '{}': {}",
                            ce.getName(), n.get("processName").asText());
                }
            }
            ce.setCreatedBy("seed");
            CanonicalEntity saved = canonicalEntityRepository.save(ce);
            ctx.entitiesByName().put(saved.getName(), saved.getId());
        }
        return ctx.entitiesByName().size();
    }

    private int seedOverlaps(UUID domainId, SeedContext ctx) throws IOException {
        List<JsonNode> nodes = SeedFiles.load(objectMapper, "demopack/entity-overlaps.json");
        int count = 0;
        for (JsonNode n : nodes) {
            UUID sourceId = ctx.entitiesByName().get(n.get("sourceEntityName").asText());
            UUID targetId = ctx.entitiesByName().get(n.get("targetEntityName").asText());
            if (sourceId == null) {
                log.warn("Source entity not found for overlap: {}", n.get("sourceEntityName").asText());
                continue;
            }
            if (targetId == null) {
                log.warn("Target entity not found for overlap: {}", n.get("targetEntityName").asText());
                continue;
            }
            EntityOverlap overlap = new EntityOverlap();
            overlap.setDomainId(domainId);
            overlap.setSourceEntityId(sourceId);
            overlap.setTargetEntityId(targetId);
            overlap.setOverlapType(SeedFiles.textOrNull(n, "overlapType"));
            if (n.has("confidence") && !n.get("confidence").isNull()) {
                overlap.setConfidence(n.get("confidence").asDouble());
            }
            if (n.has("details") && !n.get("details").isNull()) {
                overlap.setDetails(objectMapper.createObjectNode().put("summary", n.get("details").asText()));
            }
            if (n.has("aiInferred") && !n.get("aiInferred").isNull()) {
                overlap.setAiInferred(n.get("aiInferred").asBoolean());
            }
            overlap.setCreatedBy("seed");
            entityOverlapRepository.save(overlap);
            count++;
        }
        return count;
    }

    @Override
    public List<Class<? extends Seeder>> dependsOn() {
        return List.of(DomainSeeder.class, SystemSeeder.class, ProcessSeeder.class);
    }
}
