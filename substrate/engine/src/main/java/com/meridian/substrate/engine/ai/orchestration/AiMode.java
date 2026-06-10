package com.meridian.substrate.engine.ai.orchestration;

/**
 * The three AI invocation modes, per redesign doc 07 (AI Assistance Patterns).
 *
 * <ul>
 *   <li>{@link #GENERATIVE} — explicit user invocation, produces DRAFT entities
 *       grounded in Universe evidence.</li>
 *   <li>{@link #ASSISTIVE} — inline suggestions as the user works; always
 *       dismissible.</li>
 *   <li>{@link #VALIDATIVE} — audits the existing model and produces structured
 *       findings.</li>
 * </ul>
 */
public enum AiMode {
    GENERATIVE,
    ASSISTIVE,
    VALIDATIVE
}
