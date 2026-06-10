package com.meridian.substrate.app.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.CanonicalEntity;
import com.meridian.substrate.common.domain.Finding;
import com.meridian.substrate.common.domain.Integration;
import com.meridian.substrate.common.domain.Process;
import com.meridian.substrate.common.domain.SystemEntity;
import com.meridian.substrate.common.domain.enums.ManualProcessClassification;
import com.meridian.substrate.common.domain.enums.Severity;
import com.meridian.substrate.common.repo.CanonicalEntityRepository;
import com.meridian.substrate.common.repo.FindingRepository;
import com.meridian.substrate.common.repo.IntegrationRepository;
import com.meridian.substrate.common.repo.ProcessRepository;
import com.meridian.substrate.common.repo.SystemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Analyzes the current graph and produces a reduction thesis: which systems to retire,
 * which to keep, the evidence trail, and the bottom-line call ("you only need X of these N").
 *
 * Purely deterministic. No LLM calls. Every claim traces back to data in the graph.
 *
 * <p><b>MARKED FOR REPLACEMENT (port-map §2.2):</b> the dollar-savings heuristics in
 * {@link #estimateSavings} are hardcoded POC placeholders (vendor $180K / legacy $120K /
 * ancillary $80K / core $250K / default $60K / +$15K-per-finding). They must be replaced
 * with engagement-supplied cost data before any customer-facing use; the structural
 * keep/retire reasoning is the durable part of this service.
 */
@Service
public class ReductionService {

    private static final Logger log = LoggerFactory.getLogger(ReductionService.class);

    private final SystemRepository systemRepository;
    private final IntegrationRepository integrationRepository;
    private final CanonicalEntityRepository canonicalEntityRepository;
    private final FindingRepository findingRepository;
    private final ProcessRepository processRepository;

    public ReductionService(SystemRepository systemRepository,
                            IntegrationRepository integrationRepository,
                            CanonicalEntityRepository canonicalEntityRepository,
                            FindingRepository findingRepository,
                            ProcessRepository processRepository) {
        this.systemRepository = systemRepository;
        this.integrationRepository = integrationRepository;
        this.canonicalEntityRepository = canonicalEntityRepository;
        this.findingRepository = findingRepository;
        this.processRepository = processRepository;
    }

    public enum Mode { AGGRESSIVE, BALANCED, CONSERVATIVE }

    @Transactional(readOnly = true)
    public Thesis reduce(UUID domainId) {
        return reduce(domainId, Mode.AGGRESSIVE);
    }

    @Transactional(readOnly = true)
    public Thesis reduce(UUID domainId, Mode mode) {
        log.info("Running reduction [domainId={}, mode={}]", domainId, mode);

        List<SystemEntity> systems = systemRepository.findByDomainId(domainId);
        List<Integration> integrations = integrationRepository.findByDomainId(domainId);
        List<CanonicalEntity> entities = canonicalEntityRepository.findByDomainId(domainId);
        List<Finding> findings = findingRepository.findByDomainId(domainId);
        List<Process> processes = processRepository.findByDomainId(domainId);

        // Index integrations per system (in + out)
        Map<UUID, Integer> integrationDegree = new LinkedHashMap<>();
        for (Integration i : integrations) {
            if (i.getSourceSystemId() != null) {
                integrationDegree.merge(i.getSourceSystemId(), 1, Integer::sum);
            }
            if (i.getTargetSystemId() != null) {
                integrationDegree.merge(i.getTargetSystemId(), 1, Integer::sum);
            }
        }

        // Index canonical entities per system
        Map<UUID, Integer> entityCount = new LinkedHashMap<>();
        for (CanonicalEntity e : entities) {
            if (e.getSystemId() != null) {
                entityCount.merge(e.getSystemId(), 1, Integer::sum);
            }
        }

        // Index findings per system (affectedSystemIds is a JsonNode array of UUID strings)
        Map<UUID, Integer> findingCount = new LinkedHashMap<>();
        for (Finding f : findings) {
            for (UUID sid : parseUuidArray(f.getAffectedSystemIds())) {
                findingCount.merge(sid, 1, Integer::sum);
            }
        }

        // === Score each system ===
        List<SystemScore> scored = new ArrayList<>();
        for (SystemEntity s : systems) {
            int integ = integrationDegree.getOrDefault(s.getId(), 0);
            int ents = entityCount.getOrDefault(s.getId(), 0);
            int finds = findingCount.getOrDefault(s.getId(), 0);
            scored.add(new SystemScore(s, integ, ents, finds));
        }

        // === Business-line redundancy map ===
        // For each business line, rank systems by "network value" (integrations + entities).
        // Anything outside the top leader in a crowded line is a consolidation candidate.
        Map<String, List<SystemScore>> byLine = new LinkedHashMap<>();
        for (SystemScore s : scored) {
            String line = s.system.getBusinessLine();
            if (line == null || line.isBlank()) continue;
            byLine.computeIfAbsent(line, k -> new ArrayList<>()).add(s);
        }
        Map<UUID, String> consolidationInto = new LinkedHashMap<>();
        for (Map.Entry<String, List<SystemScore>> e : byLine.entrySet()) {
            List<SystemScore> group = e.getValue();
            if (group.size() < 2) continue;
            group.sort(Comparator
                    .<SystemScore>comparingInt(ss -> ss.integrations + ss.canonicalEntities)
                    .reversed());
            SystemScore leader = group.get(0);
            for (int i = 1; i < group.size(); i++) {
                SystemScore loser = group.get(i);
                // Don't consolidate "core" systems away.
                if ("core".equals(lowercase(loser.system.getSystemType()))) continue;
                consolidationInto.put(loser.system.getId(), leader.system.getName());
            }
        }

        // === Retirement threshold by mode ===
        int signalThreshold = switch (mode) {
            case AGGRESSIVE -> 1;
            case BALANCED -> 2;
            case CONSERVATIVE -> 3;
        };

        // === Decide keep vs retire ===
        List<RetireEntry> retireList = new ArrayList<>();
        List<KeepEntry> keepList = new ArrayList<>();

        for (SystemScore s : scored) {
            List<String> reasons = new ArrayList<>();
            String type = lowercase(s.system.getSystemType());
            String status = lowercase(s.system.getStatus());

            if ("deprecated".equals(status)) {
                reasons.add("Status is deprecated.");
            }
            if ("legacy".equals(type)) {
                reasons.add("Marked legacy.");
            }
            if (s.integrations <= 1) {
                reasons.add("Only " + s.integrations + " integration" +
                        (s.integrations == 1 ? "" : "s") + " — low network value.");
            }
            if (s.canonicalEntities == 0) {
                reasons.add("No canonical business data owned.");
            }
            if (s.findings >= 2) {
                reasons.add(s.findings + " open findings.");
            }
            String absorber = consolidationInto.get(s.system.getId());
            if (absorber != null) {
                reasons.add("Redundant in " + s.system.getBusinessLine() +
                        " — absorbed by " + absorber + ".");
            }

            // Hard protections regardless of mode:
            //   - "core" systems
            //   - systems that are themselves the consolidation-leader of others
            boolean isCore = "core".equals(type);
            boolean isAbsorber = consolidationInto.containsValue(s.system.getName());
            boolean protectedSystem = isCore || isAbsorber;

            if (!protectedSystem && reasons.size() >= signalThreshold) {
                retireList.add(new RetireEntry(
                        s.system.getId().toString(),
                        s.system.getName(),
                        s.system.getVendor(),
                        s.system.getBusinessLine(),
                        reasons,
                        estimateSavings(s),
                        absorber
                ));
            } else {
                List<String> keepReasons = new ArrayList<>();
                if (isCore) keepReasons.add("Core system — protected.");
                if (isAbsorber) {
                    long absorbCount = consolidationInto.values().stream()
                            .filter(v -> v.equals(s.system.getName())).count();
                    keepReasons.add("Consolidation leader — absorbs " + absorbCount + " peer"
                            + (absorbCount == 1 ? "" : "s") + ".");
                }
                if (s.integrations >= 3) keepReasons.add(s.integrations + " integrations — load-bearing.");
                if (s.canonicalEntities >= 2) keepReasons.add("Owns " + s.canonicalEntities + " canonical entities.");
                if ("active".equals(status) && keepReasons.isEmpty()) keepReasons.add("Active.");
                if (keepReasons.isEmpty()) keepReasons.add("Insufficient signal to retire.");
                keepList.add(new KeepEntry(
                        s.system.getId().toString(),
                        s.system.getName(),
                        s.system.getVendor(),
                        s.system.getBusinessLine(),
                        keepReasons
                ));
            }
        }

        // Sort retire list by savings (highest first)
        retireList.sort(Comparator.comparingDouble(RetireEntry::annualSavingsUsd).reversed());

        double totalSavings = retireList.stream().mapToDouble(RetireEntry::annualSavingsUsd).sum();

        // === Key-person risk ===
        Map<String, Integer> ownerCount = new LinkedHashMap<>();
        for (Process p : processes) {
            String owner = p.getPrimaryOwner();
            if (owner != null && !owner.isBlank()) {
                ownerCount.merge(owner, 1, Integer::sum);
            }
        }
        List<KeyPerson> keyPersons = ownerCount.entrySet().stream()
                .filter(e -> e.getValue() >= 3)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> new KeyPerson(e.getKey(), e.getValue()))
                .toList();

        // === Tribal knowledge ===
        long tribalCount = processes.stream()
                .filter(p -> p.getClassification() == ManualProcessClassification.TRIBAL_KNOWLEDGE)
                .count();

        // === Critical findings ===
        long criticalCount = findings.stream()
                .filter(f -> f.getSeverity() == Severity.CRITICAL || f.getSeverity() == Severity.HIGH)
                .count();

        // === Headline ===
        int beforeCount = systems.size();
        int keepCount = keepList.size();
        int cutCount = retireList.size();
        String headline;
        if (cutCount == 0) {
            headline = String.format(
                    "You run %d systems. The stack is already lean — no clear retirement candidates.",
                    beforeCount);
        } else if (cutCount == 1) {
            headline = String.format(
                    "You run %d systems. You need %d. One is a retirement candidate — $%s/yr on the table.",
                    beforeCount, keepCount, formatUsd(totalSavings));
        } else {
            headline = String.format(
                    "You run %d systems. You need %d. The other %d are retirement candidates — $%s/yr on the table.",
                    beforeCount, keepCount, cutCount, formatUsd(totalSavings));
        }

        String subhead;
        if (retireList.isEmpty()) {
            subhead = "No strong retirement candidates — the stack is already lean or we need more evidence.";
        } else {
            subhead = String.format(
                    "Biggest single save: %s (%s). %d finding%s still open%s.",
                    retireList.get(0).name(),
                    "$" + formatUsd(retireList.get(0).annualSavingsUsd()) + "/yr",
                    (int) criticalCount,
                    criticalCount == 1 ? "" : "s",
                    criticalCount > 0 ? " — resolve before executing cuts" : ""
            );
        }

        List<String> risks = new ArrayList<>();
        if (!keyPersons.isEmpty()) {
            KeyPerson top = keyPersons.get(0);
            risks.add(top.name() + " owns " + top.processCount() + " manual processes — single-point-of-failure.");
        }
        if (tribalCount > 0) {
            risks.add(tribalCount + " process" + (tribalCount == 1 ? "" : "es") + " flagged as tribal knowledge.");
        }
        if (criticalCount > 0) {
            risks.add(criticalCount + " high/critical finding" + (criticalCount == 1 ? "" : "s") + " affecting the ecosystem.");
        }

        return new Thesis(
                headline,
                subhead,
                beforeCount,
                keepCount,
                retireList.size(),
                formatUsd(totalSavings),
                keepList,
                retireList,
                risks,
                keyPersons,
                (int) tribalCount,
                (int) criticalCount
        );
    }

    // ---- helpers ----

    private static String lowercase(String s) {
        return s == null ? "" : s.toLowerCase();
    }

    private static List<UUID> parseUuidArray(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<UUID> out = new ArrayList<>();
        for (JsonNode el : node) {
            if (el.isTextual()) {
                try {
                    out.add(UUID.fromString(el.asText()));
                } catch (IllegalArgumentException ignored) {
                    // skip malformed UUIDs
                }
            }
        }
        return out;
    }

    private static String formatUsd(double v) {
        if (v >= 1_000_000) return String.format("%.1fM", v / 1_000_000);
        if (v >= 1_000) return String.format("%.0fK", v / 1_000);
        return String.format("%.0f", v);
    }

    /**
     * REPLACE BEFORE CUSTOMER USE (fixture seam, port-map §2.2): crude but
     * deterministic placeholder costing. Base cost by system type, scaled by
     * status and open findings. Real engagements must source these numbers
     * from engagement cost data, not this table.
     */
    private static double estimateSavings(SystemScore s) {
        // Crude but deterministic: base cost by type, scaled down by integrations retained.
        String type = lowercase(s.system.getSystemType());
        double base = switch (type) {
            case "vendor" -> 180_000;
            case "legacy" -> 120_000;
            case "ancillary" -> 80_000;
            case "core" -> 250_000;
            default -> 60_000;
        };
        // Deprecated systems we likely already stopped paying on; discount savings.
        if ("deprecated".equals(lowercase(s.system.getStatus()))) base *= 0.4;
        // More findings = higher remediation cost avoided.
        base += 15_000 * s.findings;
        return base;
    }

    // ---- records ----

    public record Thesis(
            String headline,
            String subhead,
            int systemsBefore,
            int systemsKeep,
            int systemsRetire,
            String annualSavings,
            List<KeepEntry> keepList,
            List<RetireEntry> retireList,
            List<String> keyRisks,
            List<KeyPerson> keyPersons,
            int tribalProcessCount,
            int criticalFindingCount
    ) {}

    public record KeepEntry(String id, String name, String vendor, String businessLine, List<String> reasons) {}

    public record RetireEntry(String id, String name, String vendor, String businessLine,
                              List<String> reasons, double annualSavingsUsd,
                              String absorbedBy) {}

    public record KeyPerson(String name, int processCount) {}

    private record SystemScore(SystemEntity system, int integrations, int canonicalEntities, int findings) {}
}
