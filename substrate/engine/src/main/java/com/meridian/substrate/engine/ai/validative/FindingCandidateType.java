package com.meridian.substrate.engine.ai.validative;

/**
 * The two categories of validative finding, distinguished by their grounding
 * requirement.
 *
 * <ul>
 *   <li>{@link #STRUCTURAL} — observations about the model's own state
 *       ("capability X has no processes mapped", "process Y has no owner").
 *       Self-justifying from the model graph; does NOT require Evidence
 *       citations.</li>
 *   <li>{@link #CONTENT} — observations comparing the model to source material
 *       ("capability X's description contradicts policy doc Z"). Without an
 *       Evidence citation the claim is unverifiable, so {@code CONTENT}
 *       findings without grounding are filtered by
 *       {@link ValidativeModeService}.</li>
 * </ul>
 */
public enum FindingCandidateType {
    STRUCTURAL,
    CONTENT
}
