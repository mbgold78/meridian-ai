package com.meridian.substrate.engine.agent.read;

import java.time.Instant;

/**
 * The detail DTO's {@code promotion} block (engine-spec agents.md §7.3;
 * ADR-003 D10): the pending request instant (null when none) + the latest
 * compliance review of kind PROMOTION (null until one exists).
 */
public record AgentPromotionView(Instant requestedAt, AgentReviewView latestReview) {
}
