package com.meridian.substrate.app.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.enums.DecisionType;
import com.meridian.substrate.common.repo.ContractRepository;
import com.meridian.substrate.common.repo.DesignDecisionRepository;
import com.meridian.substrate.engine.decision.log.DecisionLogService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The §13 row-3b USER-RUNNABLE acceptance sequence (engine-spec contracts.md
 * §13; ADR-002 D18), executed end-to-end over the REAL REST surface exactly
 * as the operator curl script does — DemoPack vectors, activate lever, and
 * the injected fixed clock pinned PAST 2026-05-08 (April period end + 7 grace
 * days):
 *
 * <ol>
 *   <li>run-now {@code missing-monthly-report} → {@code BREACH_DETECTED}
 *       (period 2026-04);</li>
 *   <li>breaches show DETECTED → SURFACED;</li>
 *   <li>attest 2026-05 ON_TRACK, run-now → {@code COMPLIANT};</li>
 *   <li>signals move (open-breach count reflects the open April breach;
 *       {@code pendingFederationInvitations=1} pin for vector 2);</li>
 *   <li>METRIC_THRESHOLD with ONE party's samples → {@code INDETERMINATE} +
 *       {@code ADHERENCE_STALE} in signals (no-silent-zero-yield, ADR D11);</li>
 *   <li>decline → <b>200</b> + DECLINED + monitors SUSPENDED, with the §6
 *       advisory rows ({@code REVIEW_BOUNDARY_UNCONFIGURED} on the federation
 *       vector, {@code OPEN_BREACHES_AT_DECLINE} over the open breach);</li>
 *   <li>breach human verbs SURVIVE decline (§6.3) through WAIVED, and every
 *       ADR D14 anchor (2 activations + 2 declines + 1 waiver) lands on a
 *       verifying hash chain.</li>
 * </ol>
 *
 * <p>The background sweep is pushed out ({@code PT1H}) so run-now drives
 * every evaluation deterministically; a boot-time sweep firing first is
 * harmless by construction (ADR D13 dedup — re-detection refreshes, never a
 * second row), which is itself part of what this flow proves.
 *
 * <p>H2 fast lane (dev profile, no Docker on this machine); the
 * Testcontainers-Postgres suite remains the CI acceptance gate — H2-green is
 * never acceptance for PG-only behavior (spec §13/§16).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "substrate.demo.seed=true",
        "substrate.demo.contracts.activate=true",
        "substrate.demo.fixed-clock=2026-05-12T00:00:00Z",
        "substrate.contracts.monitor.sweep-interval=PT1H",
        "spring.datasource.url=jdbc:h2:mem:accept3b;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Contract3bAcceptanceFlowTest {

    static final String CO_TRIBAL = "co-tribal-knowledge-depopulation";
    static final String FI_SHARED = "fi-shared-pre-fill-agent";
    static final String MONTHLY_REPORT_COMMITMENT =
            "Report monthly progress against the Beacon-provided template";
    static final String ACCURACY_TRIGGER = "Polestar agent reaches accuracy threshold below 90%";

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper objectMapper;
    @Autowired ContractRepository contractRepository;
    @Autowired DesignDecisionRepository designDecisionRepository;
    @Autowired DecisionLogService decisionLogService;

    private UUID domainId() {
        return contractRepository.findByContractKey(CO_TRIBAL).get(0).getDomainId();
    }

    // ------------------------------------------------------- §13 3b sequence

    @Test
    @Order(1)
    void runNow_missingMonthlyReport_breachDetected_thenSurfaced() {
        JsonNode detail = getData("/api/v1/contracts/" + CO_TRIBAL);
        assertThat(detail.get("status").asText())
                .as("activate lever precondition (§14.2)").isEqualTo("ACTIVE");
        String monitorId = monitorId(detail, "missing-monthly-report");

        // §13: run-now → BREACH_DETECTED for the closed April period.
        JsonNode run = postData("/api/v1/contracts/" + CO_TRIBAL + "/monitors/" + monitorId
                + "/run-now", null);
        assertThat(run.get("outcome").asText()).isEqualTo("BREACH_DETECTED");
        assertThat(run.get("periodKey").asText()).isEqualTo("2026-04");

        // §13: DETECTED → SURFACED, visible immediately (run-now surfaces).
        JsonNode breaches = getData("/api/v1/contracts/" + CO_TRIBAL + "/breaches");
        assertThat(breaches).hasSize(1);
        assertThat(breaches.get(0).get("status").asText()).isEqualTo("SURFACED");
        assertThat(breaches.get(0).get("periodKey").asText()).isEqualTo("2026-04");
        assertThat(breaches.get(0).get("detectedBy").asText()).isEqualTo("MONITOR");

        // §13: signals move — the open-breach count reflects the April breach.
        JsonNode signals = getData("/api/v1/contracts/signals?domainId=" + domainId());
        assertThat(signals.get("openBreaches").get("total").asLong()).isEqualTo(1);
        // §14.1 vector-2 pin: PROPOSED federation invitation, unresponded.
        assertThat(signals.get("pendingFederationInvitations").asLong()).isEqualTo(1);
    }

    @Test
    @Order(2)
    void attestMay_thenRunNow_compliant() {
        JsonNode detail = getData("/api/v1/contracts/" + CO_TRIBAL);
        String commitmentId = commitmentId(detail, MONTHLY_REPORT_COMMITMENT);
        String monitorId = monitorId(detail, "missing-monthly-report");

        JsonNode attestation = postData("/api/v1/commitments/" + commitmentId + "/attest",
                Map.of("periodKey", "2026-05", "complianceClaim", "ON_TRACK",
                        "statement", "May progress report submitted on the Beacon template"));
        assertThat(attestation.get("periodKey").asText()).isEqualTo("2026-05");
        assertThat(attestation.get("complianceClaim").asText()).isEqualTo("ON_TRACK");

        // §13: the later ON_TRACK attestation flips the monitor COMPLIANT.
        JsonNode run = postData("/api/v1/contracts/" + CO_TRIBAL + "/monitors/" + monitorId
                + "/run-now", null);
        assertThat(run.get("outcome").asText()).isEqualTo("COMPLIANT");

        // The April breach stays OPEN — monitor PASS never auto-closes (§15 #7).
        JsonNode breaches = getData("/api/v1/contracts/" + CO_TRIBAL + "/breaches");
        assertThat(breaches).hasSize(1);
        assertThat(breaches.get(0).get("status").asText()).isEqualTo("SURFACED");
    }

    @Test
    @Order(3)
    void respondAccepts_activateFederationVector() {
        JsonNode detail = getData("/api/v1/contracts/" + FI_SHARED);
        assertThat(detail.get("status").asText()).isEqualTo("PROPOSED");

        List<String> partyIds = new ArrayList<>();
        detail.get("parties").forEach(p -> partyIds.add(p.get("id").asText()));
        assertThat(partyIds).hasSize(2);

        for (String partyId : partyIds) {
            detail = postData("/api/v1/contracts/" + FI_SHARED + "/respond",
                    Map.of("partyId", partyId, "response", "ACCEPTED"));
        }
        // LAST required acceptance activates (§2.2): monitors ACTIVE.
        assertThat(detail.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(detail.get("monitors").get(0).get("status").asText()).isEqualTo("ACTIVE");

        // Curasync responded — the federation invitation is no longer pending.
        JsonNode signals = getData("/api/v1/contracts/signals?domainId=" + domainId());
        assertThat(signals.get("pendingFederationInvitations").asLong()).isZero();
    }

    @Test
    @Order(4)
    void metricThreshold_onePartysSamples_indeterminateAndAdherenceStale() {
        JsonNode detail = getData("/api/v1/contracts/" + FI_SHARED);
        String monitorId = monitorId(detail, "utilization-imbalance");
        String polestarPartyId = detail.get("parties").get(0).get("id").asText();

        // ONE party reports for the most recent CLOSED quarter (2026-Q1 at the
        // fixed 2026-05-12 clock); the other party stays silent.
        postData("/api/v1/contracts/" + FI_SHARED + "/metric-samples",
                Map.of("partyId", polestarPartyId, "metricKey", "utilization_share",
                        "value", 0.72, "periodKey", "2026-Q1"));

        // ADR D11: NEVER computes on partial data — INDETERMINATE, never a breach.
        JsonNode run = postData("/api/v1/contracts/" + FI_SHARED + "/monitors/" + monitorId
                + "/run-now", null);
        assertThat(run.get("outcome").asText()).isEqualTo("INDETERMINATE");

        JsonNode breaches = getData("/api/v1/contracts/" + FI_SHARED + "/breaches");
        assertThat(breaches).as("INDETERMINATE never opens a breach (ADR D10)").isEmpty();

        // §13 hard requirement: zero-yield surfaces — ADHERENCE_STALE in signals.
        JsonNode signals = getData("/api/v1/contracts/signals?domainId=" + domainId());
        List<String> needsInput = new ArrayList<>();
        signals.get("needsInput").forEach(n ->
                needsInput.add(n.get("contractKey").asText() + ":" + n.get("kind").asText()));
        assertThat(needsInput).contains(FI_SHARED + ":ADHERENCE_STALE");
    }

    @Test
    @Order(5)
    void declineFederationVector_200_declined_monitorsSuspended_boundaryFlagRowed() {
        JsonNode detail = getData("/api/v1/contracts/" + FI_SHARED);
        String triggerId = triggerId(detail, ACCURACY_TRIGGER);

        // §6.1: decline is a LEGITIMATE terminal action — 200 + DECLINED.
        ResponseEntity<JsonNode> response = post("/api/v1/contracts/" + FI_SHARED + "/decline",
                Map.of("declineTriggerId", triggerId,
                        "reasoning", "Sustained accuracy below the agreed 90% threshold"));
        assertThat(response.getStatusCode().value())
                .as("decline returns 200, NEVER an error code (port-map trap #8)")
                .isEqualTo(200);
        JsonNode declined = response.getBody().get("data");
        assertThat(declined.get("status").asText()).isEqualTo("DECLINED");
        declined.get("monitors").forEach(m ->
                assertThat(m.get("status").asText()).isEqualTo("SUSPENDED"));

        // §6.2 null-boundary lane: ALLOW_WITH_FLAG REVIEW_BOUNDARY_UNCONFIGURED rowed.
        JsonNode decisions = getData("/api/v1/contracts/" + FI_SHARED + "/decisions");
        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).get("decision").asText()).isEqualTo("ALLOW_WITH_FLAG");
        assertThat(decisions.get(0).get("reason").asText())
                .isEqualTo("REVIEW_BOUNDARY_UNCONFIGURED");
        assertThat(decisions.get(0).get("actionType").asText())
                .isEqualTo("contract.invoke_decline");

        // §2.2: run-now on a suspended monitor is a state error (409), not a run.
        String monitorId = declined.get("monitors").get(0).get("id").asText();
        ResponseEntity<JsonNode> suspendedRun = post("/api/v1/contracts/" + FI_SHARED
                + "/monitors/" + monitorId + "/run-now", null);
        assertThat(suspendedRun.getStatusCode().value()).isEqualTo(409);
        assertThat(suspendedRun.getBody().get("errors").get(0).get("code").asText())
                .isEqualTo("INVALID_CONTRACT_STATE");
    }

    @Test
    @Order(6)
    void declineCoTribalOverOpenBreach_flagRowed_breachSurvivesThroughWaiver() {
        JsonNode detail = getData("/api/v1/contracts/" + CO_TRIBAL);
        String triggerId = detail.get("enforcement").get("declineTriggers").get(0)
                .get("id").asText();

        ResponseEntity<JsonNode> response = post("/api/v1/contracts/" + CO_TRIBAL + "/decline",
                Map.of("declineTriggerId", triggerId,
                        "reasoning", "Beacon methodology proved insufficient for the regulated"
                                + " compliance-officer roles"));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("data").get("status").asText()).isEqualTo("DECLINED");

        // §6.3: open breach never blocks decline — flagged, never silent.
        JsonNode decisions = getData("/api/v1/contracts/" + CO_TRIBAL + "/decisions");
        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).get("reason").asText()).isEqualTo("OPEN_BREACHES_AT_DECLINE");
        assertThat(decisions.get(0).get("decision").asText()).isEqualTo("ALLOW_WITH_FLAG");

        // Breach SURVIVES decline and its human lifecycle stays available (§6.3).
        JsonNode breaches = getData("/api/v1/contracts/" + CO_TRIBAL + "/breaches");
        assertThat(breaches).hasSize(1);
        String breachId = breaches.get(0).get("id").asText();
        assertThat(breaches.get(0).get("status").asText()).isEqualTo("SURFACED");

        JsonNode acknowledged = postData("/api/v1/breaches/" + breachId + "/acknowledge",
                Map.of("note", "Reviewed with Curasync COO post-decline"));
        assertThat(acknowledged.get("status").asText()).isEqualTo("ACKNOWLEDGED");

        JsonNode waived = postData("/api/v1/breaches/" + breachId + "/waive",
                Map.of("rationale", "April reporting gap accepted given the contract was"
                        + " declined; no remediation value remains"));
        assertThat(waived.get("status").asText()).isEqualTo("WAIVED");
        assertThat(waived.get("resolutionRationale").asText()).isNotBlank();
    }

    @Test
    @Order(7)
    void adrD14Anchors_allFiveMirrored_chainVerifies() {
        UUID domainId = domainId();
        List<String> anchors = designDecisionRepository.findByDomainId(domainId).stream()
                .filter(d -> d.getDecisionType() == DecisionType.CONTRACT_ACCOUNTABILITY)
                .map(com.meridian.substrate.common.domain.DesignDecision::getTitle)
                .toList();
        // 2 activations (seed lever + respond flow) + 2 declines + 1 waiver.
        assertThat(anchors).containsExactlyInAnyOrder(
                "contract:" + CO_TRIBAL + ":CONTRACT_ACTIVATED",
                "contract:" + FI_SHARED + ":CONTRACT_ACTIVATED",
                "contract:" + FI_SHARED + ":CONTRACT_DECLINED",
                "contract:" + CO_TRIBAL + ":CONTRACT_DECLINED",
                "contract:" + CO_TRIBAL + ":BREACH_WAIVED");
        assertThat(decisionLogService.verifyChain(domainId).valid())
                .as("ADR D14 anchors land on a verifying chain (port-map trap #3)")
                .isTrue();
    }

    @Test
    @Order(8)
    void terminalContract_rejectsFurtherMutations_409NeverDeclineVocabulary() {
        JsonNode detail = getData("/api/v1/contracts/" + FI_SHARED);
        String triggerId = triggerId(detail, ACCURACY_TRIGGER);
        ResponseEntity<JsonNode> again = post("/api/v1/contracts/" + FI_SHARED + "/decline",
                Map.of("declineTriggerId", triggerId, "reasoning", "again"));
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(again.getBody().get("errors").get(0).get("code").asText())
                .isEqualTo("INVALID_CONTRACT_STATE");
    }

    // ----------------------------------------------------------------- support

    private JsonNode getData(String path) {
        ResponseEntity<JsonNode> response = rest.getForEntity(path, JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("GET %s -> %s", path, response.getStatusCode()).isTrue();
        return response.getBody().get("data");
    }

    private JsonNode postData(String path, Map<String, Object> body) {
        ResponseEntity<JsonNode> response = post(path, body);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("POST %s -> %s: %s", path, response.getStatusCode(), response.getBody())
                .isTrue();
        return response.getBody().get("data");
    }

    private ResponseEntity<JsonNode> post(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-Persona-Id", "operating-partner");
        headers.add("X-Persona-Label", "Operating Partner");
        return rest.postForEntity(path,
                new HttpEntity<>(body != null ? objectMapper.valueToTree(body) : null, headers),
                JsonNode.class);
    }

    private static String monitorId(JsonNode detail, String name) {
        for (JsonNode m : detail.get("monitors")) {
            if (name.equals(m.get("name").asText())) {
                return m.get("id").asText();
            }
        }
        throw new AssertionError("monitor '" + name + "' not found in detail");
    }

    private static String commitmentId(JsonNode detail, String text) {
        for (JsonNode party : detail.get("parties")) {
            for (JsonNode c : party.get("commitments")) {
                if (text.equals(c.get("text").asText())) {
                    return c.get("id").asText();
                }
            }
        }
        throw new AssertionError("commitment '" + text + "' not found in detail");
    }

    private static String triggerId(JsonNode detail, String text) {
        for (JsonNode t : detail.get("enforcement").get("declineTriggers")) {
            if (text.equals(t.get("text").asText())) {
                return t.get("id").asText();
            }
        }
        throw new AssertionError("decline trigger '" + text + "' not found in detail");
    }
}
