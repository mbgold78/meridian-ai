package com.meridian.substrate.app.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.app.core.service.TransformationService;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.engine.contract.gateway.Governed;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D5 thin-slice REST + governance pins (engine-spec diagnostic.md
 * §10.1/§12.5/§12.7; ADR-004 D12/D13; test plan #15):
 *
 * <ul>
 *   <li>plan / initiative / progress verbs round-trip through the envelope
 *       with the documented status codes;</li>
 *   <li>{@code progressPct} outside 0..100 → 400 (never persisted);</li>
 *   <li>COMPLETE ⇒ rendered 100 on the §12.7 subsidiary composition —
 *       the stored operator value stays untouched;</li>
 *   <li>every write verb carries {@code @Governed} with the pinned
 *       actionTypes (the §14.6 ArchUnit rule proves coverage; this pins the
 *       NAMES so a rename is a reviewed change);</li>
 *   <li>portfolio reads: unknown subsidiary → 404; the portfolio body carries
 *       the absent-vs-zero {@code computedFrom} meta block.</li>
 * </ul>
 *
 * <p>H2 fast lane — Testcontainers-Postgres remains the acceptance gate.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class TransformationApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired DomainRepository domainRepository;

    private UUID newDomain() {
        Domain d = new Domain();
        d.setName("transformation-api-" + UUID.randomUUID());
        d.setDemo(false);
        domainRepository.save(d);
        return d.getId();
    }

    private UUID createPlan(UUID domainId) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/transformation/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "domainId", domainId.toString(),
                                "name", "Plan A"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status", is("DRAFT")))
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asText());
    }

    private UUID createInitiative(UUID planId, Map<String, Object> body) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/transformation/plans/" + planId + "/initiatives")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asText());
    }

    @Test
    void planInitiativeProgressLifecycleRoundTrips() throws Exception {
        UUID domainId = newDomain();
        UUID planId = createPlan(domainId);
        UUID initiativeId = createInitiative(planId, Map.of(
                "name", "Retire bridge",
                "initiativeType", "SYSTEM_RETIRE",
                "wave", 1));

        mvc.perform(patch("/api/v1/initiatives/" + initiativeId + "/progress")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"progressPct\":55,\"progressNote\":\"halfway\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.progressPct", is(55)))
                .andExpect(jsonPath("$.data.progressNote", is("halfway")))
                .andExpect(jsonPath("$.data.progressUpdatedAt", notNullValue()))
                .andExpect(jsonPath("$.errors", hasSize(0)));
    }

    @Test
    void progressOutsideRangeIsA400AndNeverPersisted() throws Exception {
        UUID planId = createPlan(newDomain());
        UUID initiativeId = createInitiative(planId, Map.of(
                "name", "Bounded", "initiativeType", "PROCESS_REDESIGN"));

        mvc.perform(patch("/api/v1/initiatives/" + initiativeId + "/progress")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"progressPct\":101}"))
                .andExpect(status().isBadRequest());

        mvc.perform(patch("/api/v1/initiatives/" + initiativeId + "/progress")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"progressPct\":-1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void completeInitiativeRendersOneHundredOnTheSubsidiaryComposition() throws Exception {
        UUID domainId = newDomain();
        UUID planId = createPlan(domainId);
        createInitiative(planId, Map.of(
                "name", "Done already",
                "initiativeType", "CAPABILITY_ADD",
                "status", "COMPLETE",
                "progressPct", 40));        // stored 40; §12.5 renders 100

        MvcResult result = mvc.perform(get("/api/v1/portfolio/subsidiaries/" + domainId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.initiatives", hasSize(1)))
                .andExpect(jsonPath("$.data.initiatives[0].progressPct", is(100)))
                .andReturn();

        // The §12.7 composition carries the federation count + computedFrom honesty block.
        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(data.path("pendingFederationInvitations").asLong()).isZero();
        assertThat(data.path("computedFrom").path("aiReadiness").asText()).contains("omitted");
        assertThat(data.path("aiReadiness").isNull()).isTrue();
    }

    @Test
    void unknownSubsidiaryIsA404() throws Exception {
        mvc.perform(get("/api/v1/portfolio/subsidiaries/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void portfolioBodyCarriesTheComputedFromMetaBlock() throws Exception {
        newDomain();
        mvc.perform(get("/api/v1/portfolio"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.companyName", notNullValue()))
                .andExpect(jsonPath("$.data.computedFrom.stallThresholdDays", is(90)))
                .andExpect(jsonPath("$.data.computedFrom.coordinationWindowDays", is(7)))
                .andExpect(jsonPath("$.data.computedFrom.demoDomainsIncluded", is(false)))
                .andExpect(jsonPath("$.errors", hasSize(0)));
    }

    /**
     * Test plan #15 name pin: the §14.6 ArchUnit rule proves every mutating
     * verb is governed; this additionally pins the ADR-004 D12 actionType
     * NAMES so a silent rename cannot drift the guard-matching currency.
     */
    @Test
    void writeVerbsCarryThePinnedGovernedActionTypes() throws Exception {
        assertThat(governedActionType("createPlan")).isEqualTo("transformation_plan.create");
        assertThat(governedActionType("addInitiative")).isEqualTo("initiative.create");
        assertThat(governedActionType("updateProgress")).isEqualTo("initiative.update_progress");
    }

    private static String governedActionType(String methodName) {
        for (Method m : TransformationService.class.getDeclaredMethods()) {
            if (m.getName().equals(methodName) && m.isAnnotationPresent(Governed.class)) {
                return m.getAnnotation(Governed.class).actionType();
            }
        }
        throw new AssertionError("no @Governed method named " + methodName
                + " on TransformationService — test plan #15 violated");
    }
}
