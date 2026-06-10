package com.meridian.substrate.app.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.repo.DomainRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * Seeds the single demo {@link Domain}. Foundation seeder — everything else
 * depends on this one.
 *
 * <p>Substrate hardening over homepoc: the seeded domain is always marked
 * {@code isDemo=true} (redesign doc 10 / port-map §5 S1) so the DemoPack data
 * is unambiguously distinguishable from ingested production domains and the
 * orchestrator's idempotent path can scope itself to demo domains only.
 */
@Component
public class DomainSeeder implements Seeder {

    private static final Logger log = LoggerFactory.getLogger(DomainSeeder.class);

    private final ObjectMapper objectMapper;
    private final DomainRepository domainRepository;

    public DomainSeeder(ObjectMapper objectMapper, DomainRepository domainRepository) {
        this.objectMapper = objectMapper;
        this.domainRepository = domainRepository;
    }

    @Override
    public void seedBulk(SeedContext ctx) {
        try {
            List<JsonNode> nodes = SeedFiles.load(objectMapper, "demopack/domain.json");
            JsonNode n = nodes.get(0);
            Domain domain = new Domain();
            domain.setName(n.get("name").asText());
            domain.setDescription(n.get("description").asText());
            domain.setDemo(true);
            domain.setCreatedBy("seed");
            Domain saved = domainRepository.save(domain);
            ctx.setDomainId(saved.getId());
            log.info("[seed] DomainSeeder: 1 demo domain [id={}, name='{}', isDemo=true]",
                    saved.getId(), saved.getName());
        } catch (IOException e) {
            throw new RuntimeException("DomainSeeder failed", e);
        }
    }

    @Override
    public List<Class<? extends Seeder>> dependsOn() {
        return List.of();
    }
}
