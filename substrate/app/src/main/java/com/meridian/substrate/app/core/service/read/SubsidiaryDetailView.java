package com.meridian.substrate.app.core.service.read;

import com.meridian.substrate.engine.agent.read.AgentAttentionItem;
import com.meridian.substrate.engine.agent.read.AgentFleetRow;
import com.meridian.substrate.engine.cascade.read.AttentionItem;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/v1/portfolio/subsidiaries/{domainId}} body (engine-spec
 * diagnostic.md §12.7) — the per-subsidiary D5 composition: model composition
 * from existing repos, findings with the §12.4 {@code surfacedAt} projection,
 * agents as available (D3), V8 initiatives, the delegated §12.3 federation
 * count, and the attention items the COO band/strip renders.
 *
 * <p>{@code findings} is the CLIENT-SURFACE list (D10 curation gate):
 * uncurated AI findings ({@code PENDING_REVIEW}) and REJECTED/SUPERSEDED rows
 * are excluded; rows with {@code curationStatus=null} (operator/fixture) and
 * {@code ACCEPTED} appear, with {@code surfacedAt} per §12.4 —
 * {@code createdAt} for operator rows, the ACCEPT instant for AI rows
 * (EXTENSION POINT(diagnostic-4): one-line projection change if Mike rules
 * uncurated findings client-visible). {@code findingCounts} carries BOTH
 * rollup variants so the operator-vs-client difference stays visible.
 *
 * <p>{@code agentFleet}/{@code attention.agents} are {@code null} (absent)
 * when the D3 registry bean is missing — never empty-list-pretending-zero.
 */
public record SubsidiaryDetailView(
        UUID domainId,
        String name,
        String description,
        String lifecyclePhase,
        boolean phaseStatesUninitialized,
        boolean lifecycleStalled,
        String aiReadiness,
        List<CapabilityRow> capabilities,
        List<ProcessRow> processes,
        List<SystemRow> systems,
        List<FindingRow> findings,
        PortfolioView.FindingRollup findingCounts,
        PortfolioView.AgentStats agents,
        List<AgentFleetRow> agentFleet,
        List<InitiativeView> initiatives,
        long pendingFederationInvitations,
        AttentionBlock attention,
        PortfolioView.ComputedFrom computedFrom
) {

    public record CapabilityRow(UUID id, UUID parentCapabilityId, String name,
                                String displayName, int level, String status) {
    }

    public record ProcessRow(UUID id, String name, String classification) {
    }

    public record SystemRow(UUID id, String name, String vendor, String systemType) {
    }

    /**
     * Client-surfaced finding with the §12.4 projection. "Surfaced N days
     * ago" computes against server time — {@code DEMO_CURRENT_DATE} stays
     * demo-mode-only (S15).
     */
    public record FindingRow(UUID id, String severity, String findingType, String title,
                             String description, boolean aiGenerated, String curationStatus,
                             Instant surfacedAt) {
    }

    /**
     * §12.7 attention composition: decline-pending cascades (D1 feed) render
     * BESIDE in-intervention agents ([CASCADE-PROPAGATION]) — both demand
     * human attention; {@code agents} is null when the registry is absent.
     */
    public record AttentionBlock(List<AttentionItem> cascades, List<AgentAttentionItem> agents) {
    }
}
