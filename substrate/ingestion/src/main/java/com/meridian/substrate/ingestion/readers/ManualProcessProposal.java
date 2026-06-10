package com.meridian.substrate.ingestion.readers;

import java.util.List;

/**
 * Structured proposal parsed from a manual-process CSV row.
 * Corresponds to spec Section 11.1.
 */
public record ManualProcessProposal(
    String name,
    String description,
    String classification,
    String frequency,
    String primaryOwner,
    String businessLine,
    String defenseLine,
    String documentationQuality,
    Double estimatedFte,
    String riskRating,
    List<SystemLinkProposal> systemLinks,
    List<RoleProposal> roles,
    String notes
) {
    /**
     * A proposed link between a manual process and a system.
     *
     * @param systemName the target system name (e.g., "Archer")
     * @param linkType   the relationship type (e.g., "COMPENSATES_FOR")
     */
    public record SystemLinkProposal(String systemName, String linkType) {}

    /**
     * A proposed role/participant in a manual process.
     *
     * @param memberName    the participant's name (e.g., "J Smith")
     * @param participation the role type (e.g., "OWNER", "EXECUTOR")
     */
    public record RoleProposal(String memberName, String participation) {}
}
