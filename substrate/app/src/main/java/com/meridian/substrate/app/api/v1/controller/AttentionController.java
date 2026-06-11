package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.engine.agent.AgentQueryService;
import com.meridian.substrate.engine.agent.read.AgentAttentionItem;
import com.meridian.substrate.engine.cascade.CascadeQueryService;
import com.meridian.substrate.engine.cascade.read.AttentionItem;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Composed attention feed (engine-spec agents.md §13.3; ADR-003 D15):
 * {@code GET /api/v1/attention?domainId} merges
 * {@link CascadeQueryService#attention} and {@link AgentQueryService#attention}
 * into ONE list sorted destructive-first then by {@code since} desc —
 * [CASCADE-PROPAGATION]'s "decline-pending cascades render beside
 * in-intervention agents" as a single API call.
 *
 * <p>Engines stay decoupled: this is READ-SIDE composition in the app module
 * — zero engine-to-engine writes, and no cascade is ever auto-authored from
 * an intervention (queue #24 gate). Tones across both sources share the
 * info/warn/destructive wire vocabulary; destructive = leader-attention,
 * never failure.
 */
@RestController
@RequestMapping("/api/v1/attention")
public class AttentionController {

    private final CascadeQueryService cascadeQueryService;
    private final AgentQueryService agentQueryService;

    public AttentionController(CascadeQueryService cascadeQueryService,
                               AgentQueryService agentQueryService) {
        this.cascadeQueryService = cascadeQueryService;
        this.agentQueryService = agentQueryService;
    }

    /**
     * One composed row. {@code source} discriminates the union;
     * cascade rows carry {@code cascadeId}/{@code depth}/{@code ownerLabel},
     * agent rows carry {@code agentKey}/{@code registrationId} — absent
     * fields are {@code null}, never invented.
     */
    public record ComposedAttentionItem(
            String source,              // "cascade" | "agent"
            String kind,                // each engine's closed kind vocabulary
            String tone,                // info | warn | destructive
            String headline,
            Instant since,
            UUID cascadeId,
            Integer depth,
            String ownerLabel,
            String agentKey,
            UUID registrationId
    ) {

        static ComposedAttentionItem of(AttentionItem item) {
            return new ComposedAttentionItem("cascade",
                    item.kind().wireValue(), item.tone().wireValue(), item.headline(),
                    item.since(), item.cascadeId(), item.depth(), item.ownerLabel(),
                    null, null);
        }

        static ComposedAttentionItem of(AgentAttentionItem item) {
            return new ComposedAttentionItem("agent",
                    item.kind().wireValue(), item.tone().wireValue(), item.headline(),
                    item.since(), null, null, null,
                    item.agentKey(), item.registrationId());
        }
    }

    @GetMapping
    public List<ComposedAttentionItem> attention(@RequestParam UUID domainId) {
        List<ComposedAttentionItem> merged = new ArrayList<>();
        cascadeQueryService.attention(domainId, null).forEach(i -> merged.add(ComposedAttentionItem.of(i)));
        agentQueryService.attention(domainId).forEach(i -> merged.add(ComposedAttentionItem.of(i)));
        merged.sort(Comparator
                .comparingInt(AttentionController::toneRank)
                .thenComparing(ComposedAttentionItem::since,
                        Comparator.nullsLast(Comparator.reverseOrder())));
        return merged;
    }

    /** §13.3 order: destructive (leader-attention) first, then warn, then info. */
    private static int toneRank(ComposedAttentionItem item) {
        return switch (item.tone()) {
            case "destructive" -> 0;
            case "warn" -> 1;
            default -> 2;
        };
    }
}
