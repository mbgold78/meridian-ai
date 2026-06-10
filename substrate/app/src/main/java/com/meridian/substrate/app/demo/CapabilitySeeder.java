package com.meridian.substrate.app.demo;

import com.meridian.substrate.common.domain.Capability;
import com.meridian.substrate.common.domain.CapabilityProcessLink;
import com.meridian.substrate.common.domain.CapabilitySystemLink;
import com.meridian.substrate.common.domain.enums.CapabilityStatus;
import com.meridian.substrate.common.repo.CapabilityProcessLinkRepository;
import com.meridian.substrate.common.repo.CapabilityRepository;
import com.meridian.substrate.common.repo.CapabilitySystemLinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Seeds the capability tree + capability-to-system/process links. Phase 1
 * ships a single example root (customer-onboarding) + one child (kyc-verification)
 * with sample links; Phase 2 will author the real capability tree from demopack JSON.
 *
 * <p>All seeding is idempotent (per-entity-absent checks on stable seed-author
 * names), so this seeder's {@link #seedBulk(SeedContext)} delegates to
 * {@link #seedSamplesForDomain(SeedContext, UUID)} — the logic is identical in
 * both modes.
 */
@Component
public class CapabilitySeeder implements Seeder {

    private static final Logger log = LoggerFactory.getLogger(CapabilitySeeder.class);

    private final CapabilityRepository capabilityRepository;
    private final CapabilitySystemLinkRepository capabilitySystemLinkRepository;
    private final CapabilityProcessLinkRepository capabilityProcessLinkRepository;

    public CapabilitySeeder(CapabilityRepository capabilityRepository,
                             CapabilitySystemLinkRepository capabilitySystemLinkRepository,
                             CapabilityProcessLinkRepository capabilityProcessLinkRepository) {
        this.capabilityRepository = capabilityRepository;
        this.capabilitySystemLinkRepository = capabilitySystemLinkRepository;
        this.capabilityProcessLinkRepository = capabilityProcessLinkRepository;
    }

    @Override
    public void seedBulk(SeedContext ctx) {
        seedSamplesForDomain(ctx, ctx.getDomainId());
    }

    @Override
    public void seedSamplesForDomain(SeedContext ctx, UUID domainId) {
        UUID rootId = ensureCapability(domainId, null, "customer-onboarding",
                "Customer Onboarding",
                "Seeded example capability — Phase 2 will author the real tree.",
                0, CapabilityStatus.ACTIVE);
        UUID childId = ensureCapability(domainId, rootId, "kyc-verification",
                "KYC Verification", null, 1, CapabilityStatus.DRAFT);

        UUID sampleSystemId = ctx.systemsByName().values().stream().findFirst().orElse(null);
        if (sampleSystemId != null) {
            ensureSystemLink(childId, sampleSystemId, "PRIMARY");
        }

        UUID sampleProcessId = ctx.processesByName().values().stream().findFirst().orElse(null);
        if (sampleProcessId != null) {
            ensureProcessLink(childId, sampleProcessId, "SUPPORTS");
        }

        log.info("[seed] CapabilitySeeder: capabilities seeded/present [domainId={}]", domainId);
    }

    private UUID ensureCapability(UUID domainId, UUID parentId, String name,
                                   String displayName, String description,
                                   int level, CapabilityStatus status) {
        for (Capability existing : capabilityRepository.findByDomainId(domainId)) {
            if (name.equals(existing.getName())) {
                return existing.getId();
            }
        }
        Capability c = new Capability();
        c.setDomainId(domainId);
        c.setParentCapabilityId(parentId);
        c.setName(name);
        c.setDisplayName(displayName);
        c.setDescription(description);
        c.setLevel(level);
        c.setStatus(status);
        c.setCreatedBy("seed");
        Capability saved = capabilityRepository.save(c);
        log.info("[seed] CapabilitySeeder: capability [name={}, level={}]", name, level);
        return saved.getId();
    }

    private void ensureSystemLink(UUID capabilityId, UUID systemId, String linkType) {
        for (CapabilitySystemLink existing : capabilitySystemLinkRepository.findByCapabilityId(capabilityId)) {
            if (systemId.equals(existing.getSystemId())) return;
        }
        CapabilitySystemLink csl = new CapabilitySystemLink();
        csl.setCapabilityId(capabilityId);
        csl.setSystemId(systemId);
        csl.setLinkType(linkType);
        csl.setCreatedBy("seed");
        capabilitySystemLinkRepository.save(csl);
    }

    private void ensureProcessLink(UUID capabilityId, UUID processId, String linkType) {
        for (CapabilityProcessLink existing : capabilityProcessLinkRepository.findByCapabilityId(capabilityId)) {
            if (processId.equals(existing.getProcessId())) return;
        }
        CapabilityProcessLink cpl = new CapabilityProcessLink();
        cpl.setCapabilityId(capabilityId);
        cpl.setProcessId(processId);
        cpl.setLinkType(linkType);
        cpl.setCreatedBy("seed");
        capabilityProcessLinkRepository.save(cpl);
    }

    @Override
    public List<Class<? extends Seeder>> dependsOn() {
        return List.of(DomainSeeder.class, SystemSeeder.class, ProcessSeeder.class);
    }
}
