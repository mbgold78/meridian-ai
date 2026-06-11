package com.meridian.substrate.engine.decision.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.meridian.substrate.common.domain.DesignDecision;
import com.meridian.substrate.common.repo.DesignDecisionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Service implementing an append-only hash-chained decision log for design decisions.
 * <p>
 * Each decision stores a {@code previousHash} linking it to the prior decision in its
 * domain, and a {@code currentHash} computed as the SHA-256 digest of a canonical JSON
 * representation of the decision's content fields. The first decision in a domain chain
 * uses {@code "GENESIS"} as its previous hash.
 * <p>
 * This chain structure enables tamper-evident audit trails: any modification to an
 * existing decision will break the hash chain, which can be detected by
 * {@link #verifyChain(UUID)}.
 * <p>
 * <b>Canonical-JSON compatibility (port-map §6 [DECISIONLOG-CHAIN], Appendix #3):</b>
 * the hash input layout in {@link #buildCanonicalJson(DesignDecision)} is byte-for-byte
 * identical to homepoc's {@code DecisionLogService} and is pinned by
 * {@code CanonicalJsonHashVectorTest}. Do not change the field set, key order,
 * null-handling, or whitespace semantics — existing chains would become unverifiable.
 * Note that {@code SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS} sorts only
 * {@code java.util.Map} values; nested {@code JsonNode} fields (impactSummary,
 * affected*Ids, metadata) retain their insertion order, exactly as in homepoc.
 * <p>
 * <b>Concurrency (substrate fix over homepoc):</b> homepoc's {@code appendDecision} had
 * no locking — two concurrent appends could both read the same chain head and fork the
 * chain. Substrate serializes appends per domain with an in-JVM {@link ReentrantLock}
 * held across the entire append <i>transaction</i> (the write is committed via
 * {@link TransactionTemplate} inside the locked region, so the new head is visible to
 * the next lock holder before it reads the chain). This closes the fork race for the
 * single-instance-per-engagement deployment model (production plan §8). Multi-instance
 * deployments additionally need a DB-level backstop — a unique index on
 * {@code design_decisions(domain_id, previous_hash)} — tracked as an open migration item.
 * <p>
 * <b>Restart safety:</b> the service keeps no in-memory chain head — the head is
 * re-derived from the database on every append ({@link #getDecisionLog(UUID)} → last
 * entry) and exposed via {@link #getChainHead(UUID)}. On application boot,
 * {@link DecisionChainStartupVerifier} re-walks and verifies every domain chain.
 */
@Service
public class DecisionLogService {

    private static final Logger log = LoggerFactory.getLogger(DecisionLogService.class);

    private static final String GENESIS_HASH = "GENESIS";
    private static final String HASH_ALGORITHM = "SHA-256";

    /** Lock key for decisions with a null domainId (preserved homepoc edge case). */
    private static final UUID NULL_DOMAIN_LOCK_KEY = new UUID(0L, 0L);

    private final DesignDecisionRepository designDecisionRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    /** Per-domain append locks; entries are never removed (bounded by domain count). */
    private final ConcurrentHashMap<UUID, ReentrantLock> domainLocks = new ConcurrentHashMap<>();

    public DecisionLogService(DesignDecisionRepository designDecisionRepository,
                              ObjectMapper objectMapper,
                              PlatformTransactionManager transactionManager) {
        this.designDecisionRepository = designDecisionRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // REQUIRES_NEW enforces the documented append contract mechanically:
        // the chain write always commits inside the locked region, even when a
        // caller is mid-transaction (previously REQUIRED silently joined an
        // outer transaction, re-opening the fork window the lock exists to
        // close). Required for the ADR-002 D14 contract-accountability mirror,
        // which appends from an afterCommit synchronization where Spring docs
        // mandate PROPAGATION_REQUIRES_NEW for any transactional work
        // (REQUIRED there would "participate" in the already-committed
        // transaction and never flush). design_decisions.domain_id is a soft
        // ref (V2 DDL, no FK), so an early-committing append from inside a
        // larger transaction (DemoPack seeding) cannot violate constraints.
        this.transactionTemplate.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Append a new decision to the hash chain for its domain.
     * <p>
     * The method locates the most recent decision in the domain's chain, links the
     * new decision to it via {@code previousHash}, computes the {@code currentHash},
     * and persists the decision. Appends for the same domain are serialized by a
     * per-domain lock held across the append transaction, so concurrent callers
     * cannot fork the chain.
     * <p>
     * The append runs in its own {@code REQUIRES_NEW} transaction (committed before
     * the lock is released) — even when invoked from inside a caller's transaction
     * or from an {@code afterCommit} synchronization (the ADR-002 D14 contract
     * mirror), the commit can never escape the locked region. Note the flip side:
     * an append from inside a caller's transaction commits independently and
     * survives the caller's rollback.
     *
     * @param decision the decision to append (must have domainId set)
     * @return the persisted decision with hash fields populated
     */
    public DesignDecision appendDecision(DesignDecision decision) {
        UUID lockKey = decision.getDomainId() != null ? decision.getDomainId() : NULL_DOMAIN_LOCK_KEY;
        ReentrantLock lock = domainLocks.computeIfAbsent(lockKey, key -> new ReentrantLock());
        lock.lock();
        try {
            return transactionTemplate.execute(status -> doAppend(decision));
        } finally {
            lock.unlock();
        }
    }

    private DesignDecision doAppend(DesignDecision decision) {
        UUID domainId = decision.getDomainId();
        log.debug("Appending decision to chain [domainId={}, title={}]",
                domainId, decision.getTitle());

        // 1. Find the latest decision in this domain's chain (chain head is always
        //    re-derived from the database — no in-memory state to go stale on restart)
        List<DesignDecision> existingDecisions = getDecisionLog(domainId);
        String previousHash;
        if (existingDecisions.isEmpty()) {
            previousHash = GENESIS_HASH;
            log.debug("First decision in domain chain, using GENESIS hash");
        } else {
            DesignDecision latest = existingDecisions.get(existingDecisions.size() - 1);
            previousHash = latest.getCurrentHash();
            log.debug("Linking to previous decision [id={}, hash={}]",
                    latest.getId(), previousHash);
        }

        // 2. Set the previous hash
        decision.setPreviousHash(previousHash);

        // 3. Compute the current hash from canonical JSON
        String canonicalJson = buildCanonicalJson(decision);
        String currentHash = sha256(canonicalJson);
        decision.setCurrentHash(currentHash);

        log.debug("Computed hash for decision [hash={}, canonicalLength={}]",
                currentHash, canonicalJson.length());

        // 4. Save and return
        DesignDecision saved = designDecisionRepository.save(decision);
        log.info("Appended decision to chain [id={}, domainId={}, hash={}]",
                saved.getId(), domainId, currentHash);

        return saved;
    }

    /**
     * Verify the integrity of the hash chain for a given domain.
     * <p>
     * Walks all decisions in chronological order and checks that each decision's
     * {@code previousHash} matches the preceding decision's {@code currentHash},
     * and that each decision's {@code currentHash} matches a fresh SHA-256
     * computation of its canonical JSON.
     *
     * @param domainId the domain whose chain to verify
     * @return a verification result describing the chain integrity
     */
    @Transactional(readOnly = true)
    public HashChainVerification verifyChain(UUID domainId) {
        log.debug("Verifying hash chain [domainId={}]", domainId);

        List<DesignDecision> decisions = getDecisionLog(domainId);

        if (decisions.isEmpty()) {
            return new HashChainVerification(true, 0, 0, null,
                    "No decisions found for domain; chain is trivially valid.");
        }

        int verifiedLinks = 0;
        String expectedPreviousHash = GENESIS_HASH;

        for (DesignDecision decision : decisions) {
            // Verify the previous hash link
            if (!Objects.equals(decision.getPreviousHash(), expectedPreviousHash)) {
                String brokenAt = decision.getId() != null ? decision.getId().toString() : decision.getTitle();
                log.warn("Hash chain broken at decision [id={}, expected previousHash={}, actual={}]",
                        brokenAt, expectedPreviousHash, decision.getPreviousHash());
                return new HashChainVerification(false, decisions.size(), verifiedLinks,
                        brokenAt,
                        String.format("Chain broken at decision '%s': expected previousHash '%s' but found '%s'.",
                                brokenAt, expectedPreviousHash, decision.getPreviousHash()));
            }

            // Verify the current hash is correct
            String canonicalJson = buildCanonicalJson(decision);
            String recomputedHash = sha256(canonicalJson);
            if (!Objects.equals(decision.getCurrentHash(), recomputedHash)) {
                String brokenAt = decision.getId() != null ? decision.getId().toString() : decision.getTitle();
                log.warn("Hash mismatch at decision [id={}, stored={}, recomputed={}]",
                        brokenAt, decision.getCurrentHash(), recomputedHash);
                return new HashChainVerification(false, decisions.size(), verifiedLinks,
                        brokenAt,
                        String.format("Hash mismatch at decision '%s': stored hash does not match recomputed hash.",
                                brokenAt));
            }

            expectedPreviousHash = decision.getCurrentHash();
            verifiedLinks++;
        }

        log.info("Hash chain verified successfully [domainId={}, decisions={}]",
                domainId, decisions.size());
        return new HashChainVerification(true, decisions.size(), verifiedLinks, null,
                String.format("All %d decisions verified; chain is intact.", decisions.size()));
    }

    /**
     * Retrieve the full decision log for a domain, ordered chronologically by creation time.
     *
     * @param domainId the domain to query
     * @return all decisions in creation order
     */
    @Transactional(readOnly = true)
    public List<DesignDecision> getDecisionLog(UUID domainId) {
        List<DesignDecision> decisions = designDecisionRepository.findByDomainId(domainId);
        decisions.sort(Comparator.comparing(
                d -> d.getCreatedAt() != null ? d.getCreatedAt() : java.time.Instant.EPOCH));
        return decisions;
    }

    /**
     * Re-derive the current chain head for a domain from the database.
     * <p>
     * Substrate addition (restart safety): the head an append would link to is always
     * the last persisted decision's {@code currentHash}, or {@code "GENESIS"} for an
     * empty chain. There is deliberately no cached head to restore after a restart.
     *
     * @param domainId the domain to query
     * @return the chain head hash, or {@code "GENESIS"} if the domain has no decisions
     */
    @Transactional(readOnly = true)
    public String getChainHead(UUID domainId) {
        List<DesignDecision> decisions = getDecisionLog(domainId);
        if (decisions.isEmpty()) {
            return GENESIS_HASH;
        }
        return decisions.get(decisions.size() - 1).getCurrentHash();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Build a deterministic canonical JSON representation of the decision,
     * excluding volatile fields (id, createdAt, updatedAt, version) and hash
     * fields (previousHash, currentHash). Keys are sorted alphabetically.
     * <p>
     * Package-private for the pinned hash-vector test only — the byte layout is
     * frozen; see the class-level compatibility note.
     *
     * @param decision the decision to serialize
     * @return canonical JSON string
     */
    String buildCanonicalJson(DesignDecision decision) {
        try {
            ObjectMapper sortedMapper = objectMapper.copy()
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

            // Build a sorted map of the content fields only
            TreeMap<String, Object> canonical = new TreeMap<>();

            if (decision.getAffectedProcessIds() != null) {
                canonical.put("affectedProcessIds", decision.getAffectedProcessIds());
            }
            if (decision.getAffectedSystemIds() != null) {
                canonical.put("affectedSystemIds", decision.getAffectedSystemIds());
            }
            if (decision.getDecisionType() != null) {
                canonical.put("decisionType", decision.getDecisionType().name());
            }
            if (decision.getDescription() != null) {
                canonical.put("description", decision.getDescription());
            }
            if (decision.getDomainId() != null) {
                canonical.put("domainId", decision.getDomainId().toString());
            }
            if (decision.getImpactSummary() != null) {
                canonical.put("impactSummary", decision.getImpactSummary());
            }
            if (decision.getMetadata() != null) {
                canonical.put("metadata", decision.getMetadata());
            }
            canonical.put("previousHash", decision.getPreviousHash());
            if (decision.getRationale() != null) {
                canonical.put("rationale", decision.getRationale());
            }
            if (decision.getStatus() != null) {
                canonical.put("status", decision.getStatus().name());
            }
            if (decision.getTitle() != null) {
                canonical.put("title", decision.getTitle());
            }

            return sortedMapper.writeValueAsString(canonical);
        } catch (Exception e) {
            log.error("Failed to build canonical JSON for decision: {}", e.getMessage(), e);
            throw new IllegalStateException("Cannot serialize decision to canonical JSON", e);
        }
    }

    /**
     * Compute the SHA-256 hex digest of the given input string.
     * <p>
     * Package-private for the pinned hash-vector test only.
     *
     * @param input the string to hash
     * @return lowercase hex-encoded SHA-256 digest
     */
    String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the Java spec; this should never happen
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Convert a byte array to a lowercase hex string.
     *
     * @param bytes the byte array
     * @return hex-encoded string
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
