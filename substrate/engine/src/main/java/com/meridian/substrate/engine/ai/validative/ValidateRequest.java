package com.meridian.substrate.engine.ai.validative;

import com.meridian.substrate.engine.ai.orchestration.Surface;

import java.util.List;
import java.util.UUID;

/**
 * Request for {@link ValidativeModeService#validate}. {@code scope} narrows the
 * audit (e.g. {@code "capability"}, {@code "process"}, {@code "domain"});
 * {@code targetEntityIds} optionally further narrows to specific entities
 * within that scope (null / empty = audit all entities of that scope).
 */
public record ValidateRequest(
        UUID domainId,
        Surface surface,
        String scope,
        List<UUID> targetEntityIds
) {}
