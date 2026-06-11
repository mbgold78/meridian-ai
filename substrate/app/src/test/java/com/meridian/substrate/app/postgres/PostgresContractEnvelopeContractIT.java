package com.meridian.substrate.app.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractParty;
import com.meridian.substrate.common.domain.enums.ContractPartyType;
import com.meridian.substrate.common.domain.enums.ContractStatus;
import com.meridian.substrate.common.domain.enums.GuardEffect;
import com.meridian.substrate.common.domain.enums.PartyResponse;
import com.meridian.substrate.common.repo.ContractDeclineTriggerRepository;
import com.meridian.substrate.common.repo.ContractPartyRepository;
import com.meridian.substrate.common.repo.ContractRepository;
import com.meridian.substrate.engine.contract.ContractProperties;
import com.meridian.substrate.engine.contract.gateway.GovernedActions;
import com.meridian.substrate.engine.contract.registry.ContractActor;
import com.meridian.substrate.engine.contract.registry.ContractRegistryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Engine-spec contracts.md §14.4 envelope/exception contract on real
 * Postgres 16: <b>409 {@code CONTRACT_DENIAL} shape (§4.5), decline returns
 * 200 (port-map trap #8), and {@code GET /v3/api-docs} is not double-wrapped
 * (existing advice scoping holds with the new contract controllers)</b>.
 * The H2 fast-lane twins are {@code Governed3cAcceptanceTest} /
 * {@code Contract3bAcceptanceFlowTest} / {@code ResponseEnvelopeAdviceTest};
 * this class is the PG row of the §14.4 table (cascade precedent:
 * {@code PostgresCascadeReadApiIT.declineIs200WithSuccessEnvelopeOnPostgres}).
 *
 * <p>All data is seeded by driving the real {@code ContractRegistryService}
 * create/respond path (never row inserts); the guard-bearing contract is the
 * deliberately SYNTHETIC §13 3c vector — both fixture contracts compile to
 * zero guards (spec §16). DECLINE is a legitimate first-class terminal
 * resolution: the 200 lane asserts success-envelope shape, DECLINED status,
 * and monitors SUSPENDED, never error vocabulary (ADR-002 D6).
 *
 * <p>Deliberately NOT {@code @Transactional} at the class level: the
 * enforce-mode DENY lane exercises a real rollback boundary plus the
 * REQUIRES_NEW decision row, and the envelope must be observed exactly as a
 * wire client would see it.
 *
 * <p><b>Docker-less machines (e.g. the Windows dev box):</b> self-disables via
 * {@code @Testcontainers(disabledWithoutDocker = true)}; CI (ubuntu-latest)
 * runs the real gate. H2-green is never acceptance (port-map §4.4).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        // keep the background sweep out of the test window
        "substrate.contracts.monitor.sweep-interval=PT1H",
        // explicit start posture; the deny lane flips the mutable bean (ADR D4)
        "substrate.contracts.enforcement.mode=shadow"
})
@AutoConfigureMockMvc
class PostgresContractEnvelopeContractIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ContractRegistryService registryService;
    @Autowired private ContractRepository contracts;
    @Autowired private ContractPartyRepository parties;
    @Autowired private ContractDeclineTriggerRepository triggers;
    @Autowired private ContractProperties properties;

    @AfterEach
    void restoreShadowMode() {
        properties.getEnforcement().setMode("shadow");
    }

    /** §4.5 REST denial shape: 409 envelope, code CONTRACT_DENIAL, never 5xx. */
    @Test
    void enforceModeDenialIs409ContractDenialEnvelopeOnPostgres() throws Exception {
        UUID domainId = UUID.randomUUID();
        Contract guarded = activatedContract(domainId, "envelope-deny",
                new ContractRegistryService.GuardCommand(
                        GovernedActions.CONTRACT_REPORT_METRIC, null, GuardEffect.DENY, null,
                        "Synthetic DENY guard — §14.4 envelope row"));
        ContractParty party =
                parties.findByContractIdOrderBySequenceOrderAsc(guarded.getId()).get(0);
        properties.getEnforcement().setMode("enforce");

        mvc.perform(post("/api/v1/contracts/{id}/metric-samples", guarded.getId())
                        .header("X-Persona-Id", "operating-partner")
                        .header("X-Persona-Label", "Operating Partner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "partyId", party.getId().toString(),
                                "metricKey", "utilization_share",
                                "value", 0.61,
                                "periodKey", "2026-Q1"))))
                .andExpect(status().isConflict())                       // 409, never 5xx (§4.5)
                .andExpect(jsonPath("$.data", nullValue()))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].code", is("CONTRACT_DENIAL")))
                .andExpect(jsonPath("$.errors[0].message", containsString("envelope-deny")))
                .andExpect(jsonPath("$.errors[0].message", containsString("decisionId")));
    }

    /** Port-map trap #8: decline is 200 + success envelope; monitors SUSPENDED. */
    @Test
    void declineIs200WithSuccessEnvelopeOnPostgres() throws Exception {
        UUID domainId = UUID.randomUUID();
        Contract contract = activatedContract(domainId, "envelope-decline", null);
        UUID triggerId = triggers.findByContractIdOrderBySequenceOrderAsc(contract.getId())
                .get(0).getId();

        MvcResult result = mvc.perform(
                        post("/api/v1/contracts/{id}/decline", contract.getId())
                                .header("X-Persona-Id", "operating-partner")
                                .header("X-Persona-Label", "Operating Partner")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "declineTriggerId", triggerId.toString(),
                                        "reasoning",
                                        "Documented ground invoked — legitimate terminal outcome"))))
                .andExpect(status().isOk())                              // 200 ALWAYS on success
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.status", is("DECLINED")))
                .andExpect(jsonPath("$.data.monitors[0].status", is("SUSPENDED")))
                .andReturn();

        // No decline vocabulary anywhere in the error lane of the envelope.
        JsonNode envelope = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(envelope.get("errors")).isEmpty();
        assertThat(contracts.findById(contract.getId()).orElseThrow().getStatus())
                .isEqualTo(ContractStatus.DECLINED);
    }

    /** Springdoc stays outside the envelope advice with the contract controllers loaded. */
    @Test
    void apiDocsAreNotDoubleWrappedOnPostgres() throws Exception {
        MvcResult result = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(root.has("openapi"))
                .as("springdoc document served raw (advice scoping, §14.4)")
                .isTrue();
        assertThat(root.has("data")).as("no envelope 'data' wrapper").isFalse();
        assertThat(root.has("errors")).as("no envelope 'errors' wrapper").isFalse();
        // The new contract surface is actually present in the document.
        assertThat(root.get("paths").has("/api/v1/contracts/{idOrKey}/decline")).isTrue();
    }

    // ------------------------------------------------------------------ support

    /**
     * Seeds a two-party contract (one decline trigger, one MONTHLY-cadence
     * monitor, optional guard) via the real create path and activates it
     * through the real respond path (§2.2: monitors ACTIVE, guards live).
     */
    private Contract activatedContract(UUID domainId, String contractKey,
                                       ContractRegistryService.GuardCommand guard) {
        Contract contract = registryService.create(new ContractRegistryService.CreateContractCommand(
                domainId, contractKey, "cross-portfolio-opportunity",
                "Envelope IT " + contractKey, null, null, null, null, null, null,
                Instant.now(), "envelope-it",
                List.of(party("Party A"), party("Party B")),
                List.of(new ContractRegistryService.DeclineTriggerCommand(
                        "Documented legitimate ground (envelope IT)", null, null)),
                List.of(new ContractRegistryService.MonitorCommand(
                        "envelope-monitor",
                        com.meridian.substrate.common.domain.enums.MonitorRuleType.UNSTRUCTURED,
                        null, "MONTHLY", 0, null, null, null, null)),
                guard != null ? List.of(guard) : List.of(),
                List.of()));
        for (ContractParty p : parties.findByContractIdOrderBySequenceOrderAsc(contract.getId())) {
            registryService.respond(contract.getId(), new ContractRegistryService.RespondCommand(
                    p.getId(), PartyResponse.ACCEPTED, null, null,
                    "accept@" + p.getPartyLabel(), ContractActor.human("envelope-it")));
        }
        Contract activated = contracts.findById(contract.getId()).orElseThrow();
        assertThat(activated.getStatus()).isEqualTo(ContractStatus.ACTIVE);
        return activated;
    }

    private static ContractRegistryService.PartyCommand party(String label) {
        return new ContractRegistryService.PartyCommand(label, ContractPartyType.ORG_UNIT,
                null, true, List.of(new ContractRegistryService.CommitmentCommand(
                        "Commit to the envelope IT", null, null)));
    }
}
