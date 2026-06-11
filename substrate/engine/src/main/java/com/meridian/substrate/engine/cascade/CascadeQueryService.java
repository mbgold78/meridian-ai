package com.meridian.substrate.engine.cascade;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.Cascade;
import com.meridian.substrate.common.domain.CascadeLevel;
import com.meridian.substrate.common.domain.CascadeLevelAction;
import com.meridian.substrate.common.domain.CascadeNotification;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.domain.enums.CascadeAttentionSignal;
import com.meridian.substrate.common.domain.enums.CascadeLevelState;
import com.meridian.substrate.common.domain.enums.CascadeNotificationType;
import com.meridian.substrate.common.domain.enums.CascadeState;
import com.meridian.substrate.common.repo.CascadeLevelActionRepository;
import com.meridian.substrate.common.repo.CascadeLevelRepository;
import com.meridian.substrate.common.repo.CascadeNotificationRepository;
import com.meridian.substrate.common.repo.CascadeRepository;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.engine.cascade.read.AttentionItem;
import com.meridian.substrate.engine.cascade.read.CascadeActionDto;
import com.meridian.substrate.engine.cascade.read.CascadeDetail;
import com.meridian.substrate.engine.cascade.read.CascadeLevelDto;
import com.meridian.substrate.engine.cascade.read.CascadeNotificationDto;
import com.meridian.substrate.engine.cascade.read.CascadeResolution;
import com.meridian.substrate.engine.cascade.read.CascadeStats;
import com.meridian.substrate.engine.cascade.read.CascadeSummary;
import com.meridian.substrate.engine.cascade.read.SiblingDeclineAdvisory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read model for the cascade engine (engine-spec cascade.md §6.1; ADR D13):
 * maps projections to the {@code engine.cascade.read} response records in the
 * fixture vocabulary. The read model ships FIRST (§12, chunk D1 sequencing)
 * and doubles as the read-only surface orchestrator-dispatched agents may
 * query (contract doc: agents are advisors; no agent write path exists).
 *
 * <p>All projections served here are rebuildable from the append-only action
 * log (ADR D1); this service never writes. Volumes are human-scale governance
 * records — per-cascade lookups stay simple over batched joins by design
 * (ADR D8 revisit trigger covers the day that stops being true).
 */
@Service
public class CascadeQueryService {

    private static final Duration STATS_ACTIVITY_WINDOW = Duration.ofDays(7);

    private final CascadeRepository cascadeRepository;
    private final CascadeLevelRepository levelRepository;
    private final CascadeLevelActionRepository actionRepository;
    private final CascadeNotificationRepository notificationRepository;
    private final DomainRepository domainRepository;
    private final Clock clock;

    public CascadeQueryService(CascadeRepository cascadeRepository,
                               CascadeLevelRepository levelRepository,
                               CascadeLevelActionRepository actionRepository,
                               CascadeNotificationRepository notificationRepository,
                               DomainRepository domainRepository,
                               Clock clock) {
        this.cascadeRepository = cascadeRepository;
        this.levelRepository = levelRepository;
        this.actionRepository = actionRepository;
        this.notificationRepository = notificationRepository;
        this.domainRepository = domainRepository;
        this.clock = clock;
    }

    // -------------------------------------------------------------- §6.1 list

    /**
     * {@code GET /cascades} — {@code domainId} required, every other filter
     * optional. Rows come back in the §11.4 operator attention order:
     * decline-pending first, then overdue, then escalating by
     * {@code lastActivityAt} desc (absorbed and terminal rows follow).
     */
    @Transactional(readOnly = true)
    public List<CascadeSummary> list(UUID domainId, CascadeState state,
                                     CascadeAttentionSignal signal, UUID originOwnerId,
                                     UUID affectedDomainId, UUID groupId) {
        List<Cascade> cascades = state != null
                ? cascadeRepository.findByDomainIdAndCurrentState(domainId, state)
                : cascadeRepository.findByDomainId(domainId);
        List<Cascade> filtered = cascades.stream()
                .filter(c -> signal == null || c.getAttentionSignal() == signal)
                .filter(c -> originOwnerId == null || originOwnerId.equals(c.getOriginOwnerMemberId()))
                .filter(c -> affectedDomainId == null || affectedDomainId.equals(c.getAffectedDomainId()))
                .filter(c -> groupId == null || groupId.equals(c.getCascadeGroupId()))
                .toList();
        Map<UUID, String> domainNames = affectedDomainNames(filtered);
        return filtered.stream()
                .map(c -> summarize(c, levels(c.getId()), c.getAffectedDomainId() == null
                        ? null : domainNames.get(c.getAffectedDomainId())))
                .sorted(Comparator
                        .comparingInt(CascadeQueryService::attentionRank)
                        .thenComparing(CascadeSummary::lastActivityAt,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    // ------------------------------------------------------------ §6.1 detail

    /** {@code GET /cascades/{id}} — empty for an unknown id (controller maps to 404). */
    @Transactional(readOnly = true)
    public Optional<CascadeDetail> detail(UUID cascadeId) {
        return cascadeRepository.findById(cascadeId).map(cascade -> {
            List<CascadeLevel> levels = levels(cascadeId);
            String affectedDomainName = cascade.getAffectedDomainId() == null ? null
                    : domainRepository.findById(cascade.getAffectedDomainId())
                            .map(Domain::getName).orElse(null);
            return CascadeDetail.from(
                    summarize(cascade, levels, affectedDomainName),
                    levels.stream().map(CascadeLevelDto::from).toList(),
                    CascadeResolution.from(cascade),
                    siblingDeclineAdvisories(cascadeId));
        });
    }

    // ----------------------------------------------------------- §6.1 actions

    /**
     * {@code GET /cascades/{id}/actions} — the full timeline, chain_seq asc;
     * default ALL (the timeline is the only navigation — drill-to-L[N] is
     * EXTENSION POINT(cascade-3), spec §13.3). Optional zero-based
     * {@code page}/{@code size} window; a lone {@code page} uses
     * {@value #DEFAULT_PAGE_SIZE} rows, a lone {@code size} starts at page 0.
     * Empty optional for an unknown cascade id (controller maps to 404).
     */
    @Transactional(readOnly = true)
    public Optional<List<CascadeActionDto>> actions(UUID cascadeId, Integer page, Integer size) {
        if (!cascadeRepository.existsById(cascadeId)) {
            return Optional.empty();
        }
        List<CascadeActionDto> all = actionRepository.findByCascadeIdOrderByChainSeqAsc(cascadeId)
                .stream().map(CascadeActionDto::from).toList();
        if (page == null && size == null) {
            return Optional.of(all);
        }
        int effectiveSize = size != null ? size : DEFAULT_PAGE_SIZE;
        int effectivePage = page != null ? page : 0;
        if (effectivePage < 0 || effectiveSize < 1) {
            throw new IllegalArgumentException("page must be >= 0 and size >= 1");
        }
        int from = Math.min((int) Math.min((long) effectivePage * effectiveSize, all.size()), all.size());
        int to = Math.min(from + effectiveSize, all.size());
        return Optional.of(all.subList(from, to));
    }

    private static final int DEFAULT_PAGE_SIZE = 100;

    // --------------------------------------------------------- §6.1 attention

    /**
     * {@code GET /cascades/attention} — whose action is demanded right now,
     * derived from in-flight frontiers (terminal cascades demand nothing):
     *
     * <ul>
     *   <li>frontier LOOKING → {@code level-looking} (info) owned by the
     *       frontier owner;</li>
     *   <li>frontier DECLINED → {@code decline-pending-originator}
     *       (destructive, the originator may short-circuit accept, ADR D3)
     *       plus {@code respond-to-decline} (destructive) owned by the level
     *       above when that level is depth ≥ 1;</li>
     *   <li>{@code overdue} flag → an additional warn overlay item.</li>
     * </ul>
     *
     * <p>A frontier ABSORBED cascade emits no item: the §6.1 kind set is
     * closed and contains no absorbed-pending-review kind (originator review
     * expectations on absorbed work are EXTENSION POINT(cascade-2), spec
     * §13.2 — flag, don't invent).
     *
     * <p>{@code ownerId} filters to items demanded OF that member id; omitted
     * = the originator-level view for the Operating Partner strip. Order:
     * destructive, warn, info; within tone by {@code since} desc (§11.4
     * attention order).
     */
    @Transactional(readOnly = true)
    public List<AttentionItem> attention(UUID domainId, UUID ownerId) {
        List<OwnedItem> items = new ArrayList<>();
        for (Cascade cascade : cascadeRepository.findByDomainIdAndCurrentState(domainId, CascadeState.IN_FLIGHT)) {
            List<CascadeLevel> levels = levels(cascade.getId());
            CascadeLevel frontier = levels.stream()
                    .filter(l -> l.getDepth() == cascade.getFrontierDepth())
                    .findFirst().orElse(null);
            if (frontier == null) {
                continue;
            }
            if (cascade.getFrontierState() == CascadeLevelState.LOOKING) {
                items.add(new OwnedItem(new AttentionItem(cascade.getId(),
                        AttentionItem.Kind.LEVEL_LOOKING, frontier.getDepth(),
                        frontier.getOwnerLabel(), frontier.getActivatedAt(),
                        AttentionItem.Tone.INFO,
                        "Awaiting response — " + frontier.getOwnerLabel()
                                + " at L" + frontier.getDepth()),
                        frontier.getOwnerMemberId()));
            } else if (cascade.getFrontierState() == CascadeLevelState.DECLINED) {
                Instant declinedAt = frontier.getActedAt() != null
                        ? frontier.getActedAt() : frontier.getActivatedAt();
                items.add(new OwnedItem(new AttentionItem(cascade.getId(),
                        AttentionItem.Kind.DECLINE_PENDING_ORIGINATOR, frontier.getDepth(),
                        cascade.getOriginOwnerLabel(), declinedAt,
                        AttentionItem.Tone.DESTRUCTIVE,
                        "Decline pending — " + frontier.getOwnerLabel()
                                + " declined at L" + frontier.getDepth()),
                        cascade.getOriginOwnerMemberId()));
                int responseDepth = frontier.getDepth() - 1;
                if (responseDepth >= 1) {
                    CascadeLevel responseOwner = levels.stream()
                            .filter(l -> l.getDepth() == responseDepth)
                            .findFirst().orElse(null);
                    if (responseOwner != null) {
                        items.add(new OwnedItem(new AttentionItem(cascade.getId(),
                                AttentionItem.Kind.RESPOND_TO_DECLINE, responseDepth,
                                responseOwner.getOwnerLabel(), declinedAt,
                                AttentionItem.Tone.DESTRUCTIVE,
                                "Respond to decline — " + frontier.getOwnerLabel()
                                        + " declined at L" + frontier.getDepth()
                                        + "; response owned at L" + responseDepth),
                                responseOwner.getOwnerMemberId()));
                    }
                }
            }
            if (cascade.isOverdue() || frontier.isOverdue()) {
                Instant since = frontier.getResponseDeadline() != null
                        ? frontier.getResponseDeadline()
                        : (frontier.getActedAt() != null ? frontier.getActedAt() : frontier.getActivatedAt());
                items.add(new OwnedItem(new AttentionItem(cascade.getId(),
                        AttentionItem.Kind.OVERDUE, frontier.getDepth(),
                        frontier.getOwnerLabel(), since,
                        AttentionItem.Tone.WARN,
                        "Overdue — " + frontier.getOwnerLabel() + " at L" + frontier.getDepth()
                                + " past the response window"),
                        frontier.getOwnerMemberId()));
            }
        }
        return items.stream()
                .filter(o -> ownerId == null || ownerId.equals(o.ownerMemberId()))
                .map(OwnedItem::item)
                .sorted(Comparator
                        .comparingInt(CascadeQueryService::toneRank)
                        .thenComparing(AttentionItem::since,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /** §11.4 attention order: destructive (leader-attention) first, then warn, then info. */
    private static int toneRank(AttentionItem item) {
        return switch (item.tone()) {
            case DESTRUCTIVE -> 0;
            case WARN -> 1;
            case INFO -> 2;
        };
    }

    /** Internal pairing for the {@code ownerId} filter — the wire DTO carries the label only. */
    private record OwnedItem(AttentionItem item, UUID ownerMemberId) {
    }

    // ----------------------------------------------------- §6.1 notifications

    /** {@code GET /cascades/notifications} — in-app feed, newest first. */
    @Transactional(readOnly = true)
    public List<CascadeNotificationDto> notifications(UUID recipientId, boolean unreadOnly) {
        List<CascadeNotification> rows = unreadOnly
                ? notificationRepository.findByRecipientMemberIdAndReadAtIsNullOrderByCreatedAtDesc(recipientId)
                : notificationRepository.findByRecipientMemberIdOrderByCreatedAtDesc(recipientId);
        return rows.stream().map(CascadeNotificationDto::from).toList();
    }

    // ------------------------------------------------------------- §6.1 stats

    /** {@code GET /cascades/stats} — S6 engine-computed dashboard statistics. */
    @Transactional(readOnly = true)
    public CascadeStats stats(UUID domainId) {
        List<Cascade> cascades = cascadeRepository.findByDomainId(domainId);

        Map<String, Long> byState = new LinkedHashMap<>();
        for (CascadeState state : CascadeState.values()) {
            byState.put(state.wireValue(), 0L);
        }
        // In-flight attention signals only — terminal outcomes live in byState
        // (the §6.1 sample shape); overdue is an overlay count, never a signal.
        Map<String, Long> bySignal = new LinkedHashMap<>();
        for (CascadeAttentionSignal signal : EnumSet.of(CascadeAttentionSignal.DECLINE_PENDING,
                CascadeAttentionSignal.ESCALATING, CascadeAttentionSignal.ABSORBED)) {
            bySignal.put(signal.wireValue(), 0L);
        }
        long overdueCount = 0;
        long actionsLast7Days = 0;
        Instant windowStart = clock.instant().minus(STATS_ACTIVITY_WINDOW);
        for (Cascade cascade : cascades) {
            byState.merge(cascade.getCurrentState().wireValue(), 1L, Long::sum);
            if (cascade.getCurrentState() == CascadeState.IN_FLIGHT) {
                bySignal.merge(cascade.getAttentionSignal().wireValue(), 1L, Long::sum);
            }
            if (cascade.isOverdue()) {
                overdueCount++;
            }
            actionsLast7Days += actionRepository.countByCascadeIdAndActedAtAfter(cascade.getId(), windowStart);
        }
        return new CascadeStats(domainId, cascades.size(), byState, bySignal,
                overdueCount, actionsLast7Days);
    }

    // ----------------------------------------------------------------- helpers

    private List<CascadeLevel> levels(UUID cascadeId) {
        return levelRepository.findByCascadeIdOrderByDepthAsc(cascadeId);
    }

    private CascadeSummary summarize(Cascade cascade, List<CascadeLevel> levels,
                                     String affectedDomainName) {
        Instant lastActivityAt = actionRepository
                .findFirstByCascadeIdOrderByChainSeqDesc(cascade.getId())
                .map(CascadeLevelAction::getActedAt)
                .orElse(cascade.getOriginatedAt());
        return CascadeSummary.from(cascade, levels, lastActivityAt, affectedDomainName);
    }

    private Map<UUID, String> affectedDomainNames(List<Cascade> cascades) {
        List<UUID> ids = cascades.stream()
                .map(Cascade::getAffectedDomainId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return domainRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Domain::getId, Domain::getName, (a, b) -> a, HashMap::new));
    }

    /**
     * ADR D2 advisories: derived read-only from SIBLING_DECLINE outbox rows
     * anchored to THIS cascade; payload fields were written by the declining
     * cascade's action transaction (§8).
     */
    private List<SiblingDeclineAdvisory> siblingDeclineAdvisories(UUID cascadeId) {
        return notificationRepository
                .findByCascadeIdAndNotificationType(cascadeId, CascadeNotificationType.SIBLING_DECLINE)
                .stream()
                .map(row -> {
                    JsonNode payload = row.getPayload();
                    UUID decliningCascadeId = textUuid(payload, "decliningCascadeId");
                    Instant declinedAt = textInstant(payload, "declinedAt");
                    String excerpt = payload != null && payload.hasNonNull("excerpt")
                            ? payload.get("excerpt").asText() : null;
                    return new SiblingDeclineAdvisory(decliningCascadeId, row.getDepth(),
                            declinedAt, excerpt);
                })
                .sorted(Comparator.comparing(SiblingDeclineAdvisory::declinedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private static UUID textUuid(JsonNode payload, String field) {
        return payload != null && payload.hasNonNull(field)
                ? UUID.fromString(payload.get(field).asText()) : null;
    }

    private static Instant textInstant(JsonNode payload, String field) {
        return payload != null && payload.hasNonNull(field)
                ? Instant.parse(payload.get(field).asText()) : null;
    }

    /** §11.4 operator attention order for the list endpoint. */
    private static int attentionRank(CascadeSummary s) {
        if (s.currentState() != CascadeState.IN_FLIGHT) {
            return 4;
        }
        if (s.attentionSignal() == CascadeAttentionSignal.DECLINE_PENDING) {
            return 0;
        }
        if (s.overdue()) {
            return 1;
        }
        return s.attentionSignal() == CascadeAttentionSignal.ESCALATING ? 2 : 3;
    }
}
