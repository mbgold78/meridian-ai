package com.meridian.substrate.engine.diagnostic;

import com.meridian.substrate.common.domain.BaseEntity;
import com.meridian.substrate.common.repo.CanonicalEntityRepository;
import com.meridian.substrate.common.repo.ProcessRepository;
import com.meridian.substrate.common.repo.SystemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic batch planning (engine-spec diagnostic.md §4.2 step 3):
 * enumerate the enabled scopes, order entity ids deterministically
 * ({@code createdAt,id}), chunk into batches of {@code batch-size}, and
 * append ONE domain-wide batch ({@code scope='domain'}, no targets) for
 * cross-entity observations. Pure read — {@code dryRun=true} returns this
 * plan as the response body without creating anything (spec §10.1).
 *
 * <p>Scope vocabulary (spec §2/§3.1): {@code systems | processes |
 * canonical-entities} plus the planner-appended {@code domain}. An unknown
 * requested scope is a 400 ({@code IllegalArgumentException}), never silently
 * skipped — a scope typo must not quietly shrink an audit.
 */
@Service
public class DiagnosticRunPlanner {

    public static final String SCOPE_SYSTEMS = "systems";
    public static final String SCOPE_PROCESSES = "processes";
    public static final String SCOPE_CANONICAL_ENTITIES = "canonical-entities";
    public static final String SCOPE_DOMAIN = "domain";

    /** One planned batch; {@code targetEntityIds} is empty for the domain-wide batch. */
    public record PlannedBatch(int batchNo, String scope, List<UUID> targetEntityIds) {}

    private final SystemRepository systemRepository;
    private final ProcessRepository processRepository;
    private final CanonicalEntityRepository canonicalEntityRepository;

    public DiagnosticRunPlanner(SystemRepository systemRepository,
                                ProcessRepository processRepository,
                                CanonicalEntityRepository canonicalEntityRepository) {
        this.systemRepository = systemRepository;
        this.processRepository = processRepository;
        this.canonicalEntityRepository = canonicalEntityRepository;
    }

    @Transactional(readOnly = true)
    public List<PlannedBatch> plan(UUID domainId, List<String> scopes,
                                   List<UUID> targetEntityIds, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be >= 1 (was " + batchSize + ")");
        }
        Set<UUID> restriction = targetEntityIds == null || targetEntityIds.isEmpty()
                ? null
                : Set.copyOf(targetEntityIds);

        List<PlannedBatch> batches = new ArrayList<>();
        int batchNo = 1;
        for (String scope : scopes) {
            List<UUID> ids = enumerate(scope, domainId);
            if (restriction != null) {
                ids = ids.stream().filter(restriction::contains).toList();
            }
            for (int from = 0; from < ids.size(); from += batchSize) {
                batches.add(new PlannedBatch(batchNo++, scope,
                        List.copyOf(ids.subList(from, Math.min(from + batchSize, ids.size())))));
            }
        }
        // The cross-entity, domain-wide batch — always last, always present.
        batches.add(new PlannedBatch(batchNo, SCOPE_DOMAIN, List.of()));
        return List.copyOf(batches);
    }

    private List<UUID> enumerate(String scope, UUID domainId) {
        List<? extends BaseEntity> entities = switch (scope) {
            case SCOPE_SYSTEMS -> systemRepository.findByDomainId(domainId);
            case SCOPE_PROCESSES -> processRepository.findByDomainId(domainId);
            case SCOPE_CANONICAL_ENTITIES -> canonicalEntityRepository.findByDomainId(domainId);
            default -> throw new IllegalArgumentException("Unknown diagnostic scope '" + scope
                    + "' — allowed: " + SCOPE_SYSTEMS + ", " + SCOPE_PROCESSES + ", "
                    + SCOPE_CANONICAL_ENTITIES);
        };
        return entities.stream()
                .sorted(Comparator
                        .comparing(BaseEntity::getCreatedAt,
                                Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
                        .thenComparing(BaseEntity::getId))
                .map(BaseEntity::getId)
                .toList();
    }
}
