package com.meridian.substrate.engine.decision.log;

import com.meridian.substrate.common.domain.DesignDecision;
import com.meridian.substrate.common.repo.DesignDecisionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Boot-time restore safety for the decision-log hash chain (substrate addition,
 * port-map chunk C4).
 * <p>
 * {@link DecisionLogService} keeps no in-memory chain state — the head is re-derived
 * from the database on every append. This component makes the restore-after-restart
 * guarantee <i>verified</i> rather than assumed: when the application is ready it
 * re-walks every domain's chain from the database and re-validates each link and each
 * stored hash. An intact restart therefore boots with positive confirmation that the
 * persisted chains are exactly where they were left; a tampered or corrupted chain is
 * surfaced loudly in the log (and via {@link #lastResults()}) instead of being
 * discovered only when a user hits {@code GET /api/v1/decision-log/verify}.
 * <p>
 * Verification failures are deliberately non-fatal: a broken chain is an audit signal
 * to surface, not a reason to refuse boot (the verify endpoint and UI remain the
 * operator's investigation surface). Gated by
 * {@code substrate.decision-log.verify-on-boot} (default {@code true}); a missing key
 * never breaks boot.
 */
@Component
public class DecisionChainStartupVerifier {

    private static final Logger log = LoggerFactory.getLogger(DecisionChainStartupVerifier.class);

    private final DecisionLogService decisionLogService;
    private final DesignDecisionRepository designDecisionRepository;
    private final boolean verifyOnBoot;

    private final Map<UUID, HashChainVerification> lastResults = new ConcurrentHashMap<>();

    public DecisionChainStartupVerifier(
            DecisionLogService decisionLogService,
            DesignDecisionRepository designDecisionRepository,
            @Value("${substrate.decision-log.verify-on-boot:true}") boolean verifyOnBoot) {
        this.decisionLogService = decisionLogService;
        this.designDecisionRepository = designDecisionRepository;
        this.verifyOnBoot = verifyOnBoot;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!verifyOnBoot) {
            log.info("Decision-chain boot verification disabled "
                    + "(substrate.decision-log.verify-on-boot=false)");
            return;
        }
        verifyAllChains();
    }

    /**
     * Re-derive and verify every domain's decision chain from the database.
     * <p>
     * Also callable on demand (tests, ops tooling). Results are retained in
     * {@link #lastResults()} until the next run.
     *
     * @return per-domain verification results, in domain discovery order
     */
    public Map<UUID, HashChainVerification> verifyAllChains() {
        Set<UUID> domainIds = designDecisionRepository.findAll().stream()
                .map(DesignDecision::getDomainId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        long orphaned = designDecisionRepository.findAll().stream()
                .map(DesignDecision::getDomainId)
                .filter(Objects::isNull)
                .count();
        if (orphaned > 0) {
            log.warn("Decision-chain verification: {} decision(s) have null domainId "
                    + "and belong to no chain; they cannot be verified.", orphaned);
        }

        Map<UUID, HashChainVerification> results = new LinkedHashMap<>();
        for (UUID domainId : domainIds) {
            HashChainVerification verification = decisionLogService.verifyChain(domainId);
            results.put(domainId, verification);
            if (verification.valid()) {
                log.info("Decision chain verified on boot [domainId={}, decisions={}, head={}]",
                        domainId, verification.totalDecisions(),
                        decisionLogService.getChainHead(domainId));
            } else {
                log.error("DECISION CHAIN BROKEN on boot [domainId={}, firstBrokenAt={}, "
                                + "verifiedLinks={}/{}]: {}",
                        domainId, verification.firstBrokenAt(), verification.verifiedLinks(),
                        verification.totalDecisions(), verification.message());
            }
        }

        long broken = results.values().stream().filter(v -> !v.valid()).count();
        if (domainIds.isEmpty()) {
            log.info("Decision-chain boot verification: no decision chains present.");
        } else if (broken == 0) {
            log.info("Decision-chain boot verification complete: {} chain(s) intact.",
                    results.size());
        } else {
            log.error("Decision-chain boot verification complete: {} of {} chain(s) BROKEN.",
                    broken, results.size());
        }

        lastResults.clear();
        lastResults.putAll(results);
        return results;
    }

    /** The results of the most recent verification run (empty if none has run). */
    public Map<UUID, HashChainVerification> lastResults() {
        return Map.copyOf(lastResults);
    }
}
