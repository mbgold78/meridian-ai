package com.meridian.substrate.engine.decision.log;

/**
 * Result of verifying the integrity of the append-only hash chain for
 * design decisions within a domain.
 *
 * @param valid          true if the entire chain is intact
 * @param totalDecisions total number of decisions in the chain
 * @param verifiedLinks  number of links successfully verified before the first break
 * @param firstBrokenAt  identifier of the first decision with a broken link, or null if valid
 * @param message        human-readable summary of the verification result
 */
public record HashChainVerification(
    boolean valid,
    int totalDecisions,
    int verifiedLinks,
    String firstBrokenAt,
    String message
) {}
