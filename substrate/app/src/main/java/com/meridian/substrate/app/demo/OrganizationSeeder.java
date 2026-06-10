package com.meridian.substrate.app.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.OrganizationMember;
import com.meridian.substrate.common.domain.RoleOverlap;
import com.meridian.substrate.common.repo.OrganizationMemberRepository;
import com.meridian.substrate.common.repo.RoleOverlapRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Seeds organization members and the role-overlap relationships between them.
 * Role overlaps are member-to-member relationships (e.g., responsibility overlap
 * between two people), so they cluster with members rather than forming a
 * separate bounded context.
 */
@Component
public class OrganizationSeeder implements Seeder {

    private static final Logger log = LoggerFactory.getLogger(OrganizationSeeder.class);

    private final ObjectMapper objectMapper;
    private final OrganizationMemberRepository organizationMemberRepository;
    private final RoleOverlapRepository roleOverlapRepository;

    public OrganizationSeeder(ObjectMapper objectMapper,
                              OrganizationMemberRepository organizationMemberRepository,
                              RoleOverlapRepository roleOverlapRepository) {
        this.objectMapper = objectMapper;
        this.organizationMemberRepository = organizationMemberRepository;
        this.roleOverlapRepository = roleOverlapRepository;
    }

    @Override
    public void seedBulk(SeedContext ctx) {
        UUID domainId = ctx.getDomainId();
        try {
            int members = seedMembers(domainId, ctx);
            int overlaps = seedRoleOverlaps(domainId, ctx);
            log.info("[seed] OrganizationSeeder: {} members, {} role overlaps", members, overlaps);
        } catch (IOException e) {
            throw new RuntimeException("OrganizationSeeder failed", e);
        }
    }

    private int seedMembers(UUID domainId, SeedContext ctx) throws IOException {
        List<JsonNode> nodes = SeedFiles.load(objectMapper, "demopack/organization-members.json");
        for (JsonNode n : nodes) {
            OrganizationMember member = new OrganizationMember();
            member.setDomainId(domainId);
            member.setName(n.get("name").asText());
            member.setTitle(SeedFiles.textOrNull(n, "title"));
            member.setDepartment(SeedFiles.textOrNull(n, "department"));
            member.setBusinessLine(SeedFiles.textOrNull(n, "businessLine"));
            member.setReportsTo(SeedFiles.textOrNull(n, "reportsTo"));
            member.setCreatedBy("seed");
            OrganizationMember saved = organizationMemberRepository.save(member);
            ctx.membersByName().put(saved.getName(), saved.getId());
        }
        return ctx.membersByName().size();
    }

    private int seedRoleOverlaps(UUID domainId, SeedContext ctx) throws IOException {
        List<JsonNode> nodes = SeedFiles.load(objectMapper, "demopack/role-overlaps.json");
        int count = 0;
        for (JsonNode n : nodes) {
            String sourceName = n.get("sourceMemberName").asText();
            String targetName = n.get("targetMemberName").asText();
            UUID sourceId = ctx.membersByName().get(sourceName);
            UUID targetId = ctx.membersByName().get(targetName);
            if (sourceId == null) {
                log.warn("Source member not found for role overlap: {}", sourceName);
                continue;
            }
            if (targetId == null) {
                log.warn("Target member not found for role overlap: {}", targetName);
                continue;
            }
            RoleOverlap overlap = new RoleOverlap();
            overlap.setDomainId(domainId);
            overlap.setSourceMemberId(sourceId);
            overlap.setTargetMemberId(targetId);
            overlap.setOverlapType(SeedFiles.textOrNull(n, "overlapType"));
            if (n.has("details") && !n.get("details").isNull()) {
                overlap.setDetails(objectMapper.createObjectNode().put("summary", n.get("details").asText()));
            }
            if (n.has("aiInferred") && !n.get("aiInferred").isNull()) {
                overlap.setAiInferred(n.get("aiInferred").asBoolean());
            }
            overlap.setCreatedBy("seed");
            roleOverlapRepository.save(overlap);
            count++;
        }
        return count;
    }

    @Override
    public List<Class<? extends Seeder>> dependsOn() {
        return List.of(DomainSeeder.class);
    }
}
