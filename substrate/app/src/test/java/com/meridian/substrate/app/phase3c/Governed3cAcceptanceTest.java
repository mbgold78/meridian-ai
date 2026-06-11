package com.meridian.substrate.app.phase3c;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractClauseGuard;
import com.meridian.substrate.common.domain.ContractParty;
import com.meridian.substrate.common.domain.enums.ContractStatus;
import com.meridian.substrate.common.repo.ContractClauseGuardRepository;
import com.meridian.substrate.common.repo.ContractPartyRepository;
import com.meridian.substrate.common.repo.ContractRepository;
import com.meridian.substrate.engine.contract.ContractProperties;
import com.meridian.substrate.engine.contract.gateway.AdvisoryCheckService;
import com.meridian.substrate.engine.contract.gateway.GovernedActions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CI pin of the §13 row-3c USER-RUNNABLE acceptance lanes (engine-spec
 * contracts.md §13; ADR-002 D4/D18) over the REST surface and the seeded
 * {@code substrate.demo.contracts.guards} lever ({@code demo-gateway-guards}
 * — the deliberately SYNTHETIC guard-bearing contract; both fixture vectors
 * compile to zero guards, spec §16):
 *
 * <ol>
 *   <li>the lever seeds + activates the guard contract ({@code agent.promote}
 *       and {@code contract.report_metric} DENY guards live);</li>
 *   <li>{@code check_action(agent.promote)} → BLOCK verdict;</li>
 *   <li>shadow: the governed REST write SUCCEEDS (200) and
 *       {@code GET /contracts/{key}/decisions} shows WOULD_DENY;</li>
 *   <li>enforce: the same write → 409 {@code CONTRACT_DENIAL} (never 5xx).</li>
 * </ol>
 *
 * <p>The MCP halves of the row (structured {@code isError} payload,
 * {@code waive_breach} as AGENT denied) are pinned by
 * {@code ContractsMcpWriteTest}; the interactive evidence run is
 * {@code substrate/acceptance-3c.sh}. H2 fast lane — the CI
 * Testcontainers-Postgres suite stays the §14.4 gate.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "substrate.demo.seed=true",
        "substrate.demo.contracts.guards=true",
        "substrate.contracts.monitor.sweep-interval=PT1H",
        "substrate.contracts.enforcement.mode=shadow",
        "spring.datasource.url=jdbc:h2:mem:acceptance_3c;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("dev")
class Governed3cAcceptanceTest {

    static final String GUARDS_KEY = "demo-gateway-guards";

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper objectMapper;
    @Autowired ContractRepository contracts;
    @Autowired ContractPartyRepository parties;
    @Autowired ContractClauseGuardRepository guards;
    @Autowired AdvisoryCheckService advisoryCheckService;
    @Autowired ContractProperties properties;

    Contract guarded;
    ContractParty agentFleetParty;

    @BeforeEach
    void leverPreconditions() {
        guarded = contracts.findByContractKey(GUARDS_KEY).get(0);
        assertThat(guarded.getStatus())
                .as("the guards lever activates the synthetic contract (guards live iff ACTIVE)")
                .isEqualTo(ContractStatus.ACTIVE);
        List<ContractClauseGuard> guardRows = guards.findByContractId(guarded.getId());
        assertThat(guardRows).hasSize(2);
        assertThat(guardRows).allMatch(ContractClauseGuard::isActive);
        assertThat(guardRows).extracting(ContractClauseGuard::getActionType)
                .containsExactlyInAnyOrder("agent.promote", GovernedActions.CONTRACT_REPORT_METRIC);
        agentFleetParty = parties.findByContractIdOrderBySequenceOrderAsc(guarded.getId()).stream()
                .filter(p -> "demo-agent".equals(p.getMcpPrincipalId()))
                .findFirst().orElseThrow();
    }

    @AfterEach
    void restoreShadow() {
        properties.getEnforcement().setMode("shadow");
    }

    @Test
    void checkActionReturnsBlockForTheGuardedAction() {
        AdvisoryCheckService.CheckActionResult verdict =
                advisoryCheckService.check("agent.promote", null, null, null);
        assertThat(verdict.verdict()).isEqualTo(AdvisoryCheckService.VERDICT_BLOCK);
        assertThat(verdict.matchedClauses()).hasSize(1);
        assertThat(verdict.matchedClauses().get(0).contractKey()).isEqualTo(GUARDS_KEY);
    }

    @Test
    void shadowWriteSucceedsAndRowsWouldDeny_enforceWriteIs409ContractDenial() {
        // ---- shadow lane: the governed REST write SUCCEEDS …
        ResponseEntity<String> shadowWrite = reportMetric(0.61, "2026-Q1");
        assertThat(shadowWrite.getStatusCode().value())
                .as("ADR D4: shadow never blocks")
                .isEqualTo(200);

        // … and the decision log shows WOULD_DENY.
        JsonNode decisions = restData("/api/v1/contracts/" + GUARDS_KEY
                + "/decisions?decision=WOULD_DENY");
        assertThat(decisions).isNotEmpty();
        JsonNode wouldDeny = decisions.get(decisions.size() - 1);
        assertThat(wouldDeny.get("actionType").asText())
                .isEqualTo(GovernedActions.CONTRACT_REPORT_METRIC);
        assertThat(wouldDeny.get("mode").asText()).isEqualTo("shadow");

        // ---- enforce lane: the SAME write → 409 CONTRACT_DENIAL (§4.5).
        properties.getEnforcement().setMode("enforce");
        ResponseEntity<String> enforced = reportMetric(0.62, "2026-Q1");
        assertThat(enforced.getStatusCode().value()).isEqualTo(409);
        JsonNode error = readTree(enforced.getBody()).get("errors").get(0);
        assertThat(error.get("code").asText()).isEqualTo("CONTRACT_DENIAL");
        assertThat(error.get("message").asText())
                .contains(GUARDS_KEY)                  // contract key cited
                .contains("decisionId");               // decision row citation

        // The denied sample never landed (rollback) — metric-samples lane is clean
        // of the enforce-mode value while DENY decision rows persist (REQUIRES_NEW;
        // full rollback proof on real Postgres: PostgresContractGatewayDenyRollbackIT).
        JsonNode denies = restData("/api/v1/contracts/" + GUARDS_KEY + "/decisions?decision=DENY");
        assertThat(denies).isNotEmpty();
        assertThat(denies.get(denies.size() - 1).get("mode").asText()).isEqualTo("enforce");
    }

    // ----------------------------------------------------------------- support

    private ResponseEntity<String> reportMetric(double value, String periodKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-Persona-Id", "operating-partner");
        return rest.postForEntity("/api/v1/contracts/" + GUARDS_KEY + "/metric-samples",
                new HttpEntity<>(Map.of(
                        "partyId", agentFleetParty.getId().toString(),
                        "metricKey", "utilization_share",
                        "value", value,
                        "periodKey", periodKey), headers),
                String.class);
    }

    private JsonNode restData(String path) {
        ResponseEntity<String> response = rest.getForEntity(path, String.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("GET %s -> %s", path, response.getStatusCode()).isTrue();
        return readTree(response.getBody()).get("data");
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("unparseable JSON: " + json, e);
        }
    }
}
