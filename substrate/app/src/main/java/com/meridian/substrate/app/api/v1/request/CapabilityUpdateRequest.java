package com.meridian.substrate.app.api.v1.request;

import com.meridian.substrate.common.domain.enums.CapabilityStatus;

/**
 * PATCH payload for the non-structural fields of an existing Capability.
 * All fields optional (null = leave unchanged).
 *
 * <p><b>Parent changes are handled by a dedicated endpoint</b>, not this
 * one — see {@code CapabilityReparentRequest}. Keeping parent out of this
 * DTO avoids the partial-PATCH ambiguity where Jackson would default an
 * absent {@code parentCapabilityId} field to null and silently move the
 * capability to root.
 */
public record CapabilityUpdateRequest(
        String displayName,
        String description,
        CapabilityStatus status,
        String renameRationale
) {}
