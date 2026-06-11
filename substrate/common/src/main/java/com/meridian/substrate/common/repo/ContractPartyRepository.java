package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.ContractParty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Contract parties (engine-spec contracts.md §3.2 table 2). Party rows are
 * contract CONTENT — mutable only inside the ADR D7 freeze window
 * (PROPOSED + zero responses), service-enforced.
 */
@Repository
public interface ContractPartyRepository extends JpaRepository<ContractParty, UUID> {

    List<ContractParty> findByContractIdOrderBySequenceOrderAsc(UUID contractId);

    /**
     * Caller-identity binding for the MCP {@code get_obligations_for_principal}
     * read tool (§8.2): {@code mcp_principal_id} is set when the party's
     * agent/system binds via MCP (P19). Pre-Phase-5 the principal arrives as a
     * spoofable {@code X-MCP-Principal} header (S9-equivalent) and is matched
     * verbatim; Phase 5 OIDC hardens the binding.
     */
    List<ContractParty> findByMcpPrincipalId(String mcpPrincipalId);
}
