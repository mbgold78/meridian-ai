package com.meridian.substrate.engine.ai.generative;

import com.meridian.substrate.engine.ai.orchestration.Surface;

import java.util.List;
import java.util.UUID;

/**
 * Request for {@link GenerativeModeService#generate}. {@code entityType} is the
 * canonical entity name to generate (e.g. {@code "capability"},
 * {@code "process"}); {@code readerIds} optionally narrows grounding to a
 * specific subset of Universe readers (null / empty = the LLM may draw from
 * any reader visible to the persona).
 */
public record GenerateRequest(
        UUID domainId,
        Surface surface,
        String entityType,
        List<UUID> readerIds
) {}
