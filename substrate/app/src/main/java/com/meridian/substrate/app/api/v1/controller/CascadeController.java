package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.app.api.v1.request.CascadeAbsorbRequest;
import com.meridian.substrate.app.api.v1.request.CascadeCascadeRequest;
import com.meridian.substrate.app.api.v1.request.CascadeDeclineRequest;
import com.meridian.substrate.app.api.v1.request.CascadeRecascadeRequest;
import com.meridian.substrate.app.api.v1.request.CascadeResolveRequest;
import com.meridian.substrate.app.core.exception.EntityNotFoundException;
import com.meridian.substrate.app.infrastructure.web.PersonaContext;
import com.meridian.substrate.common.domain.enums.CascadeAttentionSignal;
import com.meridian.substrate.common.domain.enums.CascadeState;
import com.meridian.substrate.engine.cascade.CascadeActor;
import com.meridian.substrate.engine.cascade.CascadeLifecycleService;
import com.meridian.substrate.engine.cascade.CascadeNotificationService;
import com.meridian.substrate.engine.cascade.CascadeQueryService;
import com.meridian.substrate.engine.cascade.read.AttentionItem;
import com.meridian.substrate.engine.cascade.read.CascadeActionDto;
import com.meridian.substrate.engine.cascade.read.CascadeDetail;
import com.meridian.substrate.engine.cascade.read.CascadeNotificationDto;
import com.meridian.substrate.engine.cascade.read.CascadeStats;
import com.meridian.substrate.engine.cascade.read.CascadeSummary;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cascade READ endpoints (engine-spec cascade.md §6.1) + the operator-grade
 * COMMAND endpoints (§6.2) — `{data, meta, errors}` enveloped via
 * {@code ResponseEnvelopeAdvice}, kebab-case state vocabulary on the wire
 * (ADR D13).
 *
 * <p>The GETs shipped first per the binding read-model-first build order (§12)
 * and double as the read-only context surface for orchestrator-dispatched
 * agents (contract doc — no agent write path exists: the §6.2 commands are for
 * human operators behind persona identity).
 *
 * <p>Command semantics (§6.2): every body REQUIRES {@code idempotencyKey}
 * (replay → the original 200 semantics, no new action row); optional
 * {@code expectedChainSeq} fences against stale state (mismatch → 409
 * {@code CASCADE_STALE_STATE} with the current {@link CascadeDetail} in the
 * envelope {@code data}); a legitimate DECLINE — first-touch or
 * endorse-decline — returns <b>200 ALWAYS</b> (ADR-001 non-negotiable #1).
 * Conflict/forbidden mappings live in {@code GlobalExceptionHandler}.
 *
 * <p>Actor identity (§5.5, ADR D11): PersonaContext headers —
 * {@code X-Persona-Id} → actor member id when parseable as a UUID,
 * {@code X-Persona-Label} (falling back to the persona-level display) →
 * actor label. Spoofable in Phase 2, accepted per port-map S9; OIDC replaces
 * the source in Phase 5 with no schema change.
 *
 * <p>{@code state}/{@code signal} filters speak the wire vocabulary
 * ({@code in-flight}, {@code decline-pending}, …); an unknown value is a plain
 * 400 — decline-related vocabulary is filter input here, never an error
 * semantic (ADR-001 non-negotiable #1).
 */
@RestController
@RequestMapping("/api/v1/cascades")
public class CascadeController {

    private final CascadeQueryService queryService;
    private final CascadeLifecycleService lifecycleService;
    private final CascadeNotificationService notificationService;
    private final PersonaContext personaContext;

    public CascadeController(CascadeQueryService queryService,
                             CascadeLifecycleService lifecycleService,
                             CascadeNotificationService notificationService,
                             PersonaContext personaContext) {
        this.queryService = queryService;
        this.lifecycleService = lifecycleService;
        this.notificationService = notificationService;
        this.personaContext = personaContext;
    }

    /** §6.1 list — {@code domainId} required; every other filter optional. */
    @GetMapping
    public List<CascadeSummary> list(@RequestParam UUID domainId,
                                     @RequestParam(required = false) String state,
                                     @RequestParam(required = false) String signal,
                                     @RequestParam(required = false) UUID originOwnerId,
                                     @RequestParam(required = false) UUID affectedDomainId,
                                     @RequestParam(required = false) UUID groupId) {
        return queryService.list(domainId,
                parseWire(CascadeState.class, state, "state"),
                parseWire(CascadeAttentionSignal.class, signal, "signal"),
                originOwnerId, affectedDomainId, groupId);
    }

    /** §6.1 detail — levels 1:1 with the Beacon CascadeLevel view type; 404 unknown id. */
    @GetMapping("/{id}")
    public CascadeDetail detail(@PathVariable UUID id) {
        return queryService.detail(id)
                .orElseThrow(() -> new EntityNotFoundException("Cascade", id));
    }

    /**
     * §6.1 actions — the originator's single-throat-to-choke timeline,
     * chain_seq asc, default all rows; optional page/size window.
     */
    @GetMapping("/{id}/actions")
    public List<CascadeActionDto> actions(@PathVariable UUID id,
                                          @RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size) {
        return queryService.actions(id, page, size)
                .orElseThrow(() -> new EntityNotFoundException("Cascade", id));
    }

    /**
     * §6.1 attention — whose action is demanded right now. Omitting
     * {@code ownerId} returns the originator-level view for the Operating
     * Partner strip.
     */
    @GetMapping("/attention")
    public List<AttentionItem> attention(@RequestParam UUID domainId,
                                         @RequestParam(required = false) UUID ownerId) {
        return queryService.attention(domainId, ownerId);
    }

    /** §6.1 notifications — in-app feed over the transactional outbox (unread by default). */
    @GetMapping("/notifications")
    public List<CascadeNotificationDto> notifications(@RequestParam UUID recipientId,
                                                      @RequestParam(defaultValue = "true") boolean unreadOnly) {
        return queryService.notifications(recipientId, unreadOnly);
    }

    /** §6.1 stats — S6 engine-computed dashboard statistics. */
    @GetMapping("/stats")
    public CascadeStats stats(@RequestParam UUID domainId) {
        return queryService.stats(domainId);
    }

    // ------------------------------------------------------- §6.2 commands

    /** §6.2 / §5.3 L1 — the frontier owner refines and routes one level deeper. */
    @PostMapping("/{id}/levels/{depth}/cascade")
    public CascadeDetail cascade(@PathVariable UUID id, @PathVariable int depth,
                                 @Valid @RequestBody CascadeCascadeRequest request) {
        lifecycleService.cascade(id, depth, new CascadeLifecycleService.CascadeCommand(
                request.idempotencyKey(), request.expectedChainSeq(), currentActor(),
                request.refinedCritique(), request.nextOwnerMemberId(), request.nextOwnerLabel()));
        return requireDetail(id);
    }

    /** §6.2 / §5.3 L2/L6 — the owner takes the work; downward propagation terminates. */
    @PostMapping("/{id}/levels/{depth}/absorb")
    public CascadeDetail absorb(@PathVariable UUID id, @PathVariable int depth,
                                @Valid @RequestBody CascadeAbsorbRequest request) {
        lifecycleService.absorb(id, depth, new CascadeLifecycleService.AbsorbCommand(
                request.idempotencyKey(), request.expectedChainSeq(), currentActor(),
                request.refinedCritique()));
        return requireDetail(id);
    }

    /**
     * §6.2 / §5.3 L3 first-touch + L4 endorse-decline — documented pushback.
     * <b>200 ALWAYS for a legitimate decline</b>; 403/404/409 only for
     * forbidden/unknown/stale-or-illegal (ADR-001 non-negotiable #1).
     */
    @PostMapping("/{id}/levels/{depth}/decline")
    public CascadeDetail decline(@PathVariable UUID id, @PathVariable int depth,
                                 @Valid @RequestBody CascadeDeclineRequest request) {
        lifecycleService.decline(id, depth, new CascadeLifecycleService.DeclineCommand(
                request.idempotencyKey(), request.expectedChainSeq(), currentActor(),
                request.declineReasoning()));
        return requireDetail(id);
    }

    /** §6.2 / §5.3 L5 — revised refinement reactivates the declined level below (round + 1). */
    @PostMapping("/{id}/levels/{depth}/recascade")
    public CascadeDetail recascade(@PathVariable UUID id, @PathVariable int depth,
                                   @Valid @RequestBody CascadeRecascadeRequest request) {
        lifecycleService.recascade(id, depth, new CascadeLifecycleService.RecascadeCommand(
                request.idempotencyKey(), request.expectedChainSeq(), currentActor(),
                request.refinedCritique(), request.nextOwnerMemberId(), request.nextOwnerLabel()));
        return requireDetail(id);
    }

    /**
     * §6.2 / §5.2 A2/A3 — originator terminal resolution; both outcomes are
     * peer terminal states. {@code acceptedDeclineActionId} is required iff
     * {@code resolution=declined} (its absence/illegality → 409 per the A3
     * guard, never failure vocabulary).
     */
    @PostMapping("/{id}/resolve")
    public CascadeDetail resolve(@PathVariable UUID id,
                                 @Valid @RequestBody CascadeResolveRequest request) {
        CascadeLifecycleService.Resolution resolution = "declined".equals(request.resolution())
                ? CascadeLifecycleService.Resolution.DECLINED
                : CascadeLifecycleService.Resolution.LOOKS_GOOD;
        lifecycleService.resolve(id, new CascadeLifecycleService.ResolveCommand(
                request.idempotencyKey(), request.expectedChainSeq(), currentActor(),
                resolution, request.resolutionNote(), request.acceptedDeclineActionId()));
        return requireDetail(id);
    }

    /** §6.2 — in-app feed mark-read watermark (idempotent); 404 unknown id. */
    @PostMapping("/notifications/{id}/read")
    public CascadeNotificationDto markNotificationRead(@PathVariable UUID id) {
        return CascadeNotificationDto.from(notificationService.markRead(id));
    }

    // ----------------------------------------------------------------- helpers

    /**
     * Maps the request-scoped persona headers onto the engine's acting party
     * (engine-spec §5.5): {@code X-Persona-Id} → member id when parseable as a
     * UUID (soft ref, ADR D11); {@code X-Persona-Label} → label, falling back
     * to the persona-level display so the I5 label invariant always holds.
     */
    private CascadeActor currentActor() {
        UUID memberId = null;
        String personaId = personaContext.getPersonaId();
        if (personaId != null && !personaId.isBlank()) {
            try {
                memberId = UUID.fromString(personaId.trim());
            } catch (IllegalArgumentException notAUuid) {
                // §5.5: non-UUID persona ids carry no member identity — label-only actor.
            }
        }
        String label = personaContext.getPersonaLabel();
        if (label == null || label.isBlank()) {
            label = personaContext.getPersonaLevel();
        }
        return new CascadeActor(memberId, label);
    }

    /**
     * Re-projects the post-command {@link CascadeDetail} — the §6.2 200 body
     * (and the same shape the 409 envelope carries in {@code data}).
     */
    private CascadeDetail requireDetail(UUID id) {
        return queryService.detail(id)
                .orElseThrow(() -> new EntityNotFoundException("Cascade", id));
    }

    /**
     * Parses an optional kebab-case wire value ({@code @JsonValue} vocabulary,
     * spec §2) into its enum; unknown values become a 400 via the
     * {@code IllegalArgumentException} mapping in {@code GlobalExceptionHandler}.
     */
    private static <E extends Enum<E>> E parseWire(Class<E> type, String raw, String param) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid value for parameter '" + param + "': " + raw
                    + " (expected one of " + Arrays.stream(type.getEnumConstants())
                            .map(e -> e.name().toLowerCase(Locale.ROOT).replace('_', '-'))
                            .collect(Collectors.joining(", ")) + ")");
        }
    }
}
