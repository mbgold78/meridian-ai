package com.meridian.substrate.app.api.v1.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.enums.ReaderKind;
import com.meridian.substrate.common.domain.enums.ReaderStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ReaderRegistrationCreateRequest(
        @NotNull UUID domainId,
        @NotNull ReaderKind kind,
        @NotBlank String name,
        String uri,
        JsonNode authContext,
        ReaderStatus status
) {}
