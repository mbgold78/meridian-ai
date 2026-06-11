package com.meridian.substrate.engine.diagnostic.exception;

/**
 * REJECT without a rationale (engine-spec diagnostic.md §3.1/§5.3/§9): the
 * rationale is service-enforced NOT NULL on every REJECT — finding curation
 * and generative-draft rejection alike — and its absence is HTTP 400 with the
 * spec code {@code RATIONALE_REQUIRED} (field {@code rationale} in the
 * envelope).
 *
 * <p>This is an input-validation failure, NOT rejection vocabulary: a
 * well-formed REJECT is a legitimate product outcome and returns 200, never
 * error-colored (spec non-negotiable #3; test plan #17).
 */
public class RationaleRequiredException extends RuntimeException {

    public RationaleRequiredException(String message) {
        super(message);
    }
}
