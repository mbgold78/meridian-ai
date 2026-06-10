package com.meridian.substrate.app.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.Integration;
import com.meridian.substrate.common.domain.SystemEntity;
import com.meridian.substrate.common.domain.enums.IntegrationStatus;
import com.meridian.substrate.common.domain.enums.IntegrationType;
import com.meridian.substrate.common.repo.IntegrationRepository;
import com.meridian.substrate.common.repo.SystemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Seeds systems and the integrations between them. Canonical entities
 * (system-attached data objects) live in {@link CanonicalEntitySeeder} because
 * they also reference processes and need both seeders to have run first.
 */
@Component
public class SystemSeeder implements Seeder {

    private static final Logger log = LoggerFactory.getLogger(SystemSeeder.class);

    private final ObjectMapper objectMapper;
    private final SystemRepository systemRepository;
    private final IntegrationRepository integrationRepository;

    public SystemSeeder(ObjectMapper objectMapper,
                        SystemRepository systemRepository,
                        IntegrationRepository integrationRepository) {
        this.objectMapper = objectMapper;
        this.systemRepository = systemRepository;
        this.integrationRepository = integrationRepository;
    }

    @Override
    public void seedBulk(SeedContext ctx) {
        UUID domainId = ctx.getDomainId();
        try {
            int systemCount = seedSystems(domainId, ctx);
            int integrationCount = seedIntegrations(domainId, ctx);
            log.info("[seed] SystemSeeder: {} systems, {} integrations", systemCount, integrationCount);
        } catch (IOException e) {
            throw new RuntimeException("SystemSeeder failed", e);
        }
    }

    private int seedSystems(UUID domainId, SeedContext ctx) throws IOException {
        List<JsonNode> nodes = SeedFiles.load(objectMapper, "demopack/systems.json");
        for (JsonNode n : nodes) {
            SystemEntity entity = new SystemEntity();
            entity.setDomainId(domainId);
            entity.setName(n.get("name").asText());
            entity.setVendor(SeedFiles.textOrNull(n, "vendor"));
            entity.setDescription(SeedFiles.textOrNull(n, "description"));
            entity.setSystemType(SeedFiles.textOrNull(n, "systemType"));
            entity.setDeployedYear(SeedFiles.intOrNull(n, "deployedYear"));
            entity.setStatus(SeedFiles.textOrNull(n, "status"));
            entity.setBusinessLine(SeedFiles.textOrNull(n, "businessLine"));
            entity.setCreatedBy("seed");
            SystemEntity saved = systemRepository.save(entity);
            ctx.systemsByName().put(saved.getName(), saved.getId());
        }
        return ctx.systemsByName().size();
    }

    private int seedIntegrations(UUID domainId, SeedContext ctx) throws IOException {
        List<JsonNode> nodes = SeedFiles.load(objectMapper, "demopack/integrations.json");
        int count = 0;
        for (JsonNode n : nodes) {
            String sourceName = n.get("sourceSystemName").asText();
            String targetName = n.get("targetSystemName").asText();
            UUID sourceId = ctx.systemsByName().get(sourceName);
            UUID targetId = ctx.systemsByName().get(targetName);
            if (sourceId == null) {
                log.warn("Source system not found for integration: {}", sourceName);
                continue;
            }
            if (targetId == null) {
                log.warn("Target system not found for integration: {}", targetName);
                continue;
            }
            Integration integration = new Integration();
            integration.setDomainId(domainId);
            integration.setName(SeedFiles.textOrNull(n, "name"));
            integration.setSourceSystemId(sourceId);
            integration.setTargetSystemId(targetId);
            integration.setIntegrationType(IntegrationType.valueOf(n.get("integrationType").asText()));
            integration.setIntegrationStatus(IntegrationStatus.valueOf(n.get("integrationStatus").asText()));
            integration.setFrequency(SeedFiles.textOrNull(n, "frequency"));
            integration.setDescription(SeedFiles.textOrNull(n, "description"));
            integration.setCreatedBy("seed");
            integrationRepository.save(integration);
            count++;
        }
        return count;
    }

    @Override
    public List<Class<? extends Seeder>> dependsOn() {
        return List.of(DomainSeeder.class);
    }
}
