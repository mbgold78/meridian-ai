package com.meridian.substrate.app.api.v1.request;

import java.util.UUID;

/**
 * Payload for {@code PATCH /api/v1/capabilities/{id}/parent} — moves the
 * capability under a new parent. {@code newParentId} is explicitly nullable
 * (null = move to root). Using a dedicated endpoint + DTO avoids the
 * partial-PATCH ambiguity that would arise from adding
 * {@code parentCapabilityId} to the general {@code CapabilityUpdateRequest}.
 */
public record CapabilityReparentRequest(
        UUID newParentId
) {}
