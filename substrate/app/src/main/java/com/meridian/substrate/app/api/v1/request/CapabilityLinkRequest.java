package com.meridian.substrate.app.api.v1.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CapabilityLinkRequest(
        @NotNull UUID capabilityId,
        @NotNull UUID targetId,
        String linkType,
        Boolean aiInferred
) {}
