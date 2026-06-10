package com.meridian.substrate.app.phase1;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C1 acceptance: every read endpoint in the port-map §2.2 inventory owned by
 * this chunk responds with the {data, meta, errors} envelope on the dev (H2)
 * profile. POST /sources/probe and /readers/{id}/probe are deliberately NOT
 * exercised — they perform live outbound HTTP (SSRF risk surface) and have no
 * place in an offline unit lane.
 *
 * The transformation endpoint is asserted in its FLAG-OFF default state here;
 * the flag-on fixture path is covered by TransformationPlanFixtureEnabledTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class C1EndpointInventoryTest extends Phase1StageASupport {

    @Autowired MockMvc mvc;

    @Test
    void domainEndpointsRespond() throws Exception {
        UUID domainId = createDomain();

        mvc.perform(get("/api/v1/domains"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$.meta.requestId", notNullValue()));

        mvc.perform(get("/api/v1/domains/{id}", domainId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", is(domainId.toString())));
    }

    @Test
    void missingDomainYieldsEnvelopedNotFound() throws Exception {
        mvc.perform(get("/api/v1/domains/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code", is("NOT_FOUND")));
    }

    @Test
    void systemEndpointsRespond() throws Exception {
        UUID domainId = createDomain();
        UUID systemId = createSystem(domainId);

        mvc.perform(get("/api/v1/systems").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)));

        mvc.perform(get("/api/v1/systems/{id}", systemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", is(systemId.toString())));

        mvc.perform(get("/api/v1/systems/{id}/entities", systemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));

        mvc.perform(get("/api/v1/systems/{id}/integrations", systemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void processEndpointsRespond() throws Exception {
        UUID domainId = createDomain();
        UUID processId = createProcess(domainId);

        mvc.perform(get("/api/v1/processes").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)));

        mvc.perform(get("/api/v1/processes/{id}", processId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", is(processId.toString())));

        mvc.perform(get("/api/v1/processes/{id}/system-links", processId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));

        mvc.perform(get("/api/v1/processes/{id}/roles", processId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void organizationEndpointsRespond() throws Exception {
        UUID domainId = createDomain();

        mvc.perform(get("/api/v1/organization/members").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));

        mvc.perform(get("/api/v1/organization/role-overlaps").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void ecosystemEndpointsRespond() throws Exception {
        UUID domainId = createDomain();
        createSystem(domainId);
        createProcess(domainId);

        mvc.perform(get("/api/v1/ecosystem/graph").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.systems", hasSize(1)))
                .andExpect(jsonPath("$.data.processes", hasSize(1)))
                .andExpect(jsonPath("$.data.integrations", hasSize(0)))
                .andExpect(jsonPath("$.data.canonicalEntities", hasSize(0)))
                .andExpect(jsonPath("$.data.entityOverlaps", hasSize(0)));

        mvc.perform(get("/api/v1/ecosystem/findings").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));

        mvc.perform(get("/api/v1/ecosystem/overlaps").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));

        mvc.perform(get("/api/v1/ecosystem/manual-dependencies").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void evidenceEndpointsRespond() throws Exception {
        UUID domainId = createDomain();

        mvc.perform(get("/api/v1/evidence/entity").param("entityId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));

        mvc.perform(get("/api/v1/evidence/domain").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void intelligenceEndpointsRespond() throws Exception {
        UUID domainId = createDomain();
        createSystem(domainId);
        createProcess(domainId);

        mvc.perform(get("/api/v1/intelligence/thesis").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.systemsBefore", is(1)))
                .andExpect(jsonPath("$.data.headline", notNullValue()));

        // mode parameter accepted
        mvc.perform(get("/api/v1/intelligence/thesis")
                        .param("domainId", domainId.toString())
                        .param("mode", "CONSERVATIVE"))
                .andExpect(status().isOk());

        // projectName pins the S10 fixture-seam fix: config-driven engagement
        // display name (safe default), never the baked homepoc demo identity.
        mvc.perform(get("/api/v1/intelligence/assistant").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projectName", is("Substrate Engagement")))
                .andExpect(jsonPath("$.data.phases", hasSize(4)))
                .andExpect(jsonPath("$.data.nextAction", notNullValue()));
    }

    @Test
    void healthAndInfoRespond() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("UP")))
                .andExpect(jsonPath("$.data.timestamp", notNullValue()));

        mvc.perform(get("/api/v1/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name", is("meridian-substrate")))
                .andExpect(jsonPath("$.data.version", is("0.1.0")));
    }

    @Test
    void transformationPlanIsGatedOffByDefault() throws Exception {
        mvc.perform(get("/api/v1/transformation/plan"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code", is("NOT_FOUND")));
    }
}
