package com.meridian.substrate.app.phase2b1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.app.api.v1.request.CapabilityCreateRequest;
import com.meridian.substrate.app.api.v1.request.CapabilityLinkRequest;
import com.meridian.substrate.app.api.v1.request.CapabilityUpdateRequest;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.domain.Process;
import com.meridian.substrate.common.domain.SystemEntity;
import com.meridian.substrate.common.domain.enums.CapabilityStatus;
import com.meridian.substrate.common.domain.enums.ManualProcessClassification;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.common.repo.ProcessRepository;
import com.meridian.substrate.common.repo.SystemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Capability CRUD + rename-history + link endpoints — ported from homepoc's
 * phase1 {@code CapabilityCrudTest} (kept with the phase2b1 capability/branding
 * vertical because the capability surface is owned by chunk C2; the homepoc
 * {@code Phase1StageASupport} helpers are inlined so this class has no
 * dependency on C1's phase1 test-support files). Substrate dev profile.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class CapabilityCrudTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DomainRepository domainRepository;
    @Autowired SystemRepository systemRepository;
    @Autowired ProcessRepository processRepository;

    @Test
    void createReadUpdateCapability_writesNameHistoryOnRename() throws Exception {
        UUID domainId = createDomain();

        CapabilityCreateRequest create = new CapabilityCreateRequest(
                domainId, null, "customer-onboarding", "Customer Onboarding",
                "description", 0, CapabilityStatus.DRAFT, false);

        MvcResult created = mvc.perform(post("/api/v1/capabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(create)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.displayName", is("Customer Onboarding")))
                .andExpect(jsonPath("$.data.status", is("DRAFT")))
                .andReturn();

        UUID capId = UUID.fromString(json.readTree(created.getResponse().getContentAsString())
                .get("data").get("id").asText());

        mvc.perform(get("/api/v1/capabilities/{id}", capId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name", is("customer-onboarding")));

        mvc.perform(get("/api/v1/capabilities").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)));

        // Rename displayName → should write a history row.
        CapabilityUpdateRequest rename = new CapabilityUpdateRequest(
                "Customer Acquisition", null, null, "align with new taxonomy");
        mvc.perform(patch("/api/v1/capabilities/{id}", capId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(rename)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName", is("Customer Acquisition")));

        mvc.perform(get("/api/v1/capabilities/{id}/name-history", capId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].fromDisplayName", is("Customer Onboarding")))
                .andExpect(jsonPath("$.data[0].toDisplayName", is("Customer Acquisition")));
    }

    @Test
    void createCapabilityProcessLink_listsUnderCapability() throws Exception {
        UUID domainId = createDomain();
        UUID processId = createProcess(domainId);
        UUID capId = createCapability(domainId);

        CapabilityLinkRequest link = new CapabilityLinkRequest(capId, processId, "SUPPORTS", false);
        mvc.perform(post("/api/v1/capabilities/process-links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(link)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.linkType", is("SUPPORTS")));

        mvc.perform(get("/api/v1/capabilities/{id}/process-links", capId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].processId", is(processId.toString())));
    }

    @Test
    void createCapabilitySystemLink_listsUnderCapability() throws Exception {
        UUID domainId = createDomain();
        UUID systemId = createSystem(domainId);
        UUID capId = createCapability(domainId);

        CapabilityLinkRequest link = new CapabilityLinkRequest(capId, systemId, "PRIMARY", false);
        mvc.perform(post("/api/v1/capabilities/system-links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(link)))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/capabilities/{id}/system-links", capId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].systemId", is(systemId.toString())));
    }

    // ---- helpers (inlined homepoc Phase1StageASupport) -----------------------

    private UUID createCapability(UUID domainId) throws Exception {
        CapabilityCreateRequest create = new CapabilityCreateRequest(
                domainId, null, "test-" + UUID.randomUUID(), "Test Cap",
                null, 0, CapabilityStatus.ACTIVE, false);
        MvcResult r = mvc.perform(post("/api/v1/capabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(create)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(json.readTree(r.getResponse().getContentAsString())
                .get("data").get("id").asText());
    }

    private UUID createDomain() {
        Domain d = new Domain();
        d.setName("Test Domain " + UUID.randomUUID());
        d.setCreatedBy("test");
        return domainRepository.save(d).getId();
    }

    private UUID createSystem(UUID domainId) {
        SystemEntity s = new SystemEntity();
        s.setDomainId(domainId);
        s.setName("Test System " + UUID.randomUUID());
        s.setCreatedBy("test");
        return systemRepository.save(s).getId();
    }

    private UUID createProcess(UUID domainId) {
        Process p = new Process();
        p.setDomainId(domainId);
        p.setName("Test Process " + UUID.randomUUID());
        p.setDescription("Test description");
        p.setClassification(ManualProcessClassification.BRIDGE);
        p.setCreatedBy("test");
        return processRepository.save(p).getId();
    }
}
