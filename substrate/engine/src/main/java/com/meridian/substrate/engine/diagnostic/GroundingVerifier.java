package com.meridian.substrate.engine.diagnostic;

import com.meridian.substrate.common.domain.Evidence;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Grounding-verification SPI (engine-spec diagnostic.md §6.2; ADR-004 D7).
 * v1 ships exactly one implementation — {@link ExistenceGroundingVerifier}
 * ({@code mode=EXISTENCE_ONLY}): a citation is verified to EXIST as an
 * in-domain Evidence row, nothing more. Semantic support is human-verified at
 * acceptance — product copy must never say "machine-verified grounding".
 *
 * <p>EXTENSION POINT(diagnostic-1): semantic grounding verification. Any
 * future SEMANTIC verifier (LLM-judge / embedding score) is <b>advisory-only
 * by contract</b> — it may lower confidence, never auto-pass (P7: an LLM
 * opinion about grounding is itself an AI claim). Unblocks via
 * regulated-buyer demand; flag, don't invent.
 */
public interface GroundingVerifier {

    String MODE_EXISTENCE_ONLY = "EXISTENCE_ONLY";

    /** The persisted {@code groundingMode} value this verifier vouches for. */
    String mode();

    /**
     * Resolves the cited evidence ids that (still) exist as in-domain Evidence
     * rows, in citation order. Ids that do not resolve — or resolve outside
     * the domain — are simply absent from the result: the CALLER decides what
     * a missing citation means (the commit step fails THAT candidate as
     * ungrounded, never the batch — spec §5.2).
     */
    Map<UUID, Evidence> resolveInDomain(List<UUID> evidenceIds, UUID domainId);
}
