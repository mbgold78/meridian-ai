package com.meridian.substrate.app.phase1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.app.api.v1.request.ReaderRegistrationCreateRequest;
import com.meridian.substrate.common.domain.enums.ReaderKind;
import com.meridian.substrate.common.domain.enums.ReaderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Ported homepoc phase1 test (dev-profile convention; see PhaseStateCrudTest).
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class ReaderRegistrationCrudTest extends Phase1StageASupport {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void registerAndListReader() throws Exception {
        UUID domainId = createDomain();

        ReaderRegistrationCreateRequest req = new ReaderRegistrationCreateRequest(
                domainId, ReaderKind.STRUCTURED, "Converter ETL", null, null, ReaderStatus.ACTIVE);

        mvc.perform(post("/api/v1/readers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.kind", is("STRUCTURED")))
                .andExpect(jsonPath("$.data.status", is("ACTIVE")));

        mvc.perform(get("/api/v1/readers").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].name", is("Converter ETL")));
    }

    @Test
    void deleteReader() throws Exception {
        UUID domainId = createDomain();

        ReaderRegistrationCreateRequest req = new ReaderRegistrationCreateRequest(
                domainId, ReaderKind.DOCUMENT, "SOP corpus", null, null, null);

        String body = mvc.perform(post("/api/v1/readers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(req)))
                .andExpect(status().isCreated())
                // status omitted in request defaults to ACTIVE
                .andExpect(jsonPath("$.data.status", is("ACTIVE")))
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(body).path("data").path("id").asText();

        mvc.perform(delete("/api/v1/readers/{id}", id))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/readers").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }
}
