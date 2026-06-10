package com.meridian.substrate.app.api.v1.controller;

import java.util.UUID;

/**
 * Request payload for chat interactions.
 *
 * @param domainId the active domain UUID
 * @param message  the user's natural-language message
 */
public record ChatRequest(UUID domainId, String message) {}
