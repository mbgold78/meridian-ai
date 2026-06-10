package com.meridian.substrate.app.core.service;

import com.meridian.substrate.common.domain.DesignDecision;
import com.meridian.substrate.common.domain.Finding;
import com.meridian.substrate.common.domain.Integration;
import com.meridian.substrate.common.domain.Process;
import com.meridian.substrate.common.domain.SystemEntity;
import com.meridian.substrate.common.domain.enums.ManualProcessClassification;
import com.meridian.substrate.common.domain.enums.Severity;
import com.meridian.substrate.common.repo.CanonicalEntityRepository;
import com.meridian.substrate.common.repo.DesignDecisionRepository;
import com.meridian.substrate.common.repo.FindingRepository;
import com.meridian.substrate.common.repo.IntegrationRepository;
import com.meridian.substrate.common.repo.ProcessRepository;
import com.meridian.substrate.common.repo.SystemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Structured guide. Evaluates a declarative rubric against the current graph and
 * tells the user: what phase are we in, what percentage complete, what's blocking,
 * and what's the single next best action.
 *
 * No autonomy. No background loop. Reacts to the graph as it is right now.
 */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

    private final SystemRepository systemRepository;
    private final IntegrationRepository integrationRepository;
    private final FindingRepository findingRepository;
    private final ProcessRepository processRepository;
    private final CanonicalEntityRepository canonicalEntityRepository;
    private final DesignDecisionRepository decisionRepository;

    /**
     * Engagement display name surfaced as {@code projectName} in the assistant
     * status. Homepoc hardcoded the demo identity "Meridian Bancorp · Risk
     * Consolidation" here; substrate severs that fixture seam (port-map S10) and
     * sources it from per-engagement config with a neutral safe default so a
     * missing key never breaks boot.
     */
    private final String engagementDisplayName;

    public AssistantService(SystemRepository systemRepository,
                            IntegrationRepository integrationRepository,
                            FindingRepository findingRepository,
                            ProcessRepository processRepository,
                            CanonicalEntityRepository canonicalEntityRepository,
                            DesignDecisionRepository decisionRepository,
                            @Value("${substrate.engagement.display-name:Substrate Engagement}")
                            String engagementDisplayName) {
        this.systemRepository = systemRepository;
        this.integrationRepository = integrationRepository;
        this.findingRepository = findingRepository;
        this.processRepository = processRepository;
        this.canonicalEntityRepository = canonicalEntityRepository;
        this.decisionRepository = decisionRepository;
        this.engagementDisplayName = engagementDisplayName;
    }

    @Transactional(readOnly = true)
    public AssistantStatus evaluate(UUID domainId) {
        log.info("Evaluating assistant rubric [domainId={}]", domainId);

        List<SystemEntity> systems = systemRepository.findByDomainId(domainId);
        List<Integration> integrations = integrationRepository.findByDomainId(domainId);
        List<Finding> findings = findingRepository.findByDomainId(domainId);
        List<Process> processes = processRepository.findByDomainId(domainId);
        int entityCount = canonicalEntityRepository.findByDomainId(domainId).size();
        List<DesignDecision> decisions = decisionRepository.findByDomainId(domainId);

        List<Blocker> blockers = new ArrayList<>();
        List<Phase> phases = new ArrayList<>();

        // Phase 1: Sources — ingestion coverage
        List<Check> sourceChecks = new ArrayList<>();
        sourceChecks.add(booleanCheck(
                "At least one structured source ingested",
                !systems.isEmpty(),
                systems.isEmpty() ? "No systems on file — connect a system inventory source." : null));
        sourceChecks.add(booleanCheck(
                "Manual processes captured",
                !processes.isEmpty(),
                processes.isEmpty() ? "No manual processes — ingest SOP documents or interview a process owner." : null));
        sourceChecks.add(booleanCheck(
                "Canonical business entities mapped",
                entityCount > 0,
                entityCount == 0 ? "No canonical entities — ingest a data dictionary or business glossary." : null));
        phases.add(phaseFromChecks("Sources", "Ingest enough to reason about", sourceChecks));

        // Phase 2: Coverage quality
        List<Check> coverageChecks = new ArrayList<>();
        int missingOwners = 0;
        for (SystemEntity s : systems) {
            if (isBlank(s.getVendor()) && isBlank(s.getBusinessLine())) missingOwners++;
        }
        coverageChecks.add(numericCheck(
                "All systems have vendor or business line",
                systems.size() - missingOwners, systems.size(),
                missingOwners == 0 ? null :
                        missingOwners + " system" + (missingOwners == 1 ? "" : "s") + " missing vendor/business line."));

        int processesNoOwner = (int) processes.stream().filter(p -> isBlank(p.getPrimaryOwner())).count();
        coverageChecks.add(numericCheck(
                "All processes have an owner",
                processes.size() - processesNoOwner, processes.size(),
                processesNoOwner == 0 ? null :
                        processesNoOwner + " process" + (processesNoOwner == 1 ? "" : "es") + " missing an owner."));

        int systemsWithIntegration = integratedSystemCount(systems, integrations);
        int pctIntegrated = systems.isEmpty() ? 0 : (100 * systemsWithIntegration) / systems.size();
        coverageChecks.add(numericCheck(
                "At least 70% of systems have ≥1 integration",
                systemsWithIntegration, systems.size(),
                pctIntegrated >= 70 ? null :
                        pctIntegrated + "% of systems are integrated — goal is 70%. Capture more data flows."));
        phases.add(phaseFromChecks("Coverage", "Fill in the missing attributes", coverageChecks));

        // Phase 3: Conflicts — these become blockers on the reduction phase
        List<Check> conflictChecks = new ArrayList<>();
        int highFindings = (int) findings.stream()
                .filter(f -> f.getSeverity() == Severity.HIGH || f.getSeverity() == Severity.CRITICAL)
                .count();
        conflictChecks.add(booleanCheck(
                "No unresolved critical findings",
                highFindings == 0,
                highFindings == 0 ? null :
                        highFindings + " high/critical finding" + (highFindings == 1 ? "" : "s") +
                                " still open — resolve before executing cuts."));
        if (highFindings > 0) {
            blockers.add(new Blocker(
                    "unresolved-findings",
                    "Unresolved critical risks",
                    highFindings + " high/critical finding" + (highFindings == 1 ? "" : "s") +
                            " must be reviewed before retirement decisions are safe.",
                    "Open Risks",
                    "risks"
            ));
        }

        // Tribal knowledge conflicts
        long tribalCount = processes.stream()
                .filter(p -> p.getClassification() == ManualProcessClassification.TRIBAL_KNOWLEDGE)
                .count();
        conflictChecks.add(booleanCheck(
                "No tribal-knowledge processes unmitigated",
                tribalCount == 0,
                tribalCount == 0 ? null :
                        tribalCount + " process" + (tribalCount == 1 ? "" : "es") +
                                " rely on tribal knowledge — document or assign a backup."));
        if (tribalCount > 0) {
            blockers.add(new Blocker(
                    "tribal-knowledge",
                    "Tribal knowledge is load-bearing",
                    tribalCount + " manual process" + (tribalCount == 1 ? "" : "es") +
                            " have single-owner, undocumented workflows.",
                    "Interview owners",
                    "roadmap"
            ));
        }

        // Duplicate-candidate detection (fuzzy name match on systems)
        List<String[]> dupes = findDuplicateCandidates(systems);
        conflictChecks.add(booleanCheck(
                "No duplicate-candidate systems",
                dupes.isEmpty(),
                dupes.isEmpty() ? null :
                        dupes.size() + " possible duplicate" + (dupes.size() == 1 ? "" : "s") +
                                " — confirm or merge."));
        for (String[] pair : dupes) {
            blockers.add(new Blocker(
                    "dupe-" + pair[0].hashCode(),
                    "Possible duplicate: " + pair[0] + " ↔ " + pair[1],
                    "Names look similar. Confirm if they're the same system (merge) or distinct (keep separate).",
                    "Review systems",
                    null
            ));
        }
        phases.add(phaseFromChecks("Conflicts", "Resolve before cutting", conflictChecks));

        // Phase 4: Reduction readiness
        List<Check> reductionChecks = new ArrayList<>();
        reductionChecks.add(booleanCheck(
                "Graph has enough systems to reduce (≥ 3)",
                systems.size() >= 3,
                systems.size() >= 3 ? null : "Need at least 3 systems to reason about consolidation."));
        reductionChecks.add(booleanCheck(
                "Critical conflicts resolved",
                blockers.stream().noneMatch(b -> b.id().equals("unresolved-findings")),
                null));
        reductionChecks.add(booleanCheck(
                "Decisions log available for traceability",
                decisions.size() > 0,
                decisions.isEmpty() ? "No decisions logged yet. Once you act, decisions will appear here for audit." : null));
        phases.add(phaseFromChecks("Reduction", "Produce and defend the thesis", reductionChecks));

        // Overall completeness
        int totalChecks = phases.stream().mapToInt(p -> p.checks().size()).sum();
        long passed = phases.stream().flatMap(p -> p.checks().stream()).filter(Check::passed).count();
        int completeness = totalChecks == 0 ? 0 : (int) ((100 * passed) / totalChecks);

        // Next best action — first failing check, in phase order
        String nextAction = phases.stream()
                .flatMap(p -> p.checks().stream())
                .filter(c -> !c.passed() && c.gapDescription() != null)
                .map(Check::gapDescription)
                .findFirst()
                .orElse("You're in good shape — publish the thesis when ready.");

        boolean readyForReduction = phases.stream()
                .filter(p -> !p.name().equals("Reduction"))
                .allMatch(p -> p.status() != PhaseStatus.BLOCKED);

        return new AssistantStatus(
                engagementDisplayName,
                completeness,
                phases,
                blockers,
                nextAction,
                readyForReduction
        );
    }

    // ---- helpers ----

    private Check booleanCheck(String label, boolean passed, String gap) {
        return new Check(label, passed, gap);
    }

    private Check numericCheck(String label, int achieved, int total, String gap) {
        boolean passed = total == 0 ? true : (achieved * 100 / Math.max(total, 1)) >= 70;
        return new Check(label + " (" + achieved + "/" + total + ")", passed, gap);
    }

    private Phase phaseFromChecks(String name, String subtitle, List<Check> checks) {
        long passed = checks.stream().filter(Check::passed).count();
        PhaseStatus status;
        if (passed == checks.size()) status = PhaseStatus.DONE;
        else if (passed == 0) status = PhaseStatus.PENDING;
        else status = PhaseStatus.IN_PROGRESS;
        // Block status if any critical check is failing — for POC, all Conflicts failures block
        if ("Conflicts".equals(name) && passed < checks.size()) status = PhaseStatus.BLOCKED;
        return new Phase(name, subtitle, status, checks);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static int integratedSystemCount(List<SystemEntity> systems, List<Integration> integrations) {
        Map<UUID, Boolean> has = new LinkedHashMap<>();
        for (SystemEntity s : systems) has.put(s.getId(), false);
        for (Integration i : integrations) {
            if (i.getSourceSystemId() != null) has.put(i.getSourceSystemId(), true);
            if (i.getTargetSystemId() != null) has.put(i.getTargetSystemId(), true);
        }
        return (int) has.values().stream().filter(b -> b).count();
    }

    /** Simple fuzzy-name match on systems: normalize, find pairs with shared significant tokens. */
    private static List<String[]> findDuplicateCandidates(List<SystemEntity> systems) {
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < systems.size(); i++) {
            for (int j = i + 1; j < systems.size(); j++) {
                String a = normalize(systems.get(i).getName());
                String b = normalize(systems.get(j).getName());
                if (a.isBlank() || b.isBlank()) continue;
                if (a.equals(b) || tokenOverlap(a, b) >= 2) {
                    out.add(new String[]{systems.get(i).getName(), systems.get(j).getName()});
                }
            }
        }
        return out;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private static int tokenOverlap(String a, String b) {
        String[] at = a.split(" ");
        String[] bt = b.split(" ");
        int count = 0;
        for (String t : at) {
            if (t.length() < 4) continue;
            for (String u : bt) {
                if (t.equals(u)) { count++; break; }
            }
        }
        return count;
    }

    // ---- DTO records ----

    public record AssistantStatus(
            String projectName,
            int completenessPercent,
            List<Phase> phases,
            List<Blocker> blockers,
            String nextAction,
            boolean readyForReduction
    ) {}

    public record Phase(String name, String subtitle, PhaseStatus status, List<Check> checks) {}

    public record Check(String label, boolean passed, String gapDescription) {}

    public record Blocker(String id, String title, String description, String actionLabel, String actionTab) {}

    public enum PhaseStatus { PENDING, IN_PROGRESS, BLOCKED, DONE }
}
