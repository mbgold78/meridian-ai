package com.meridian.substrate.app.phase1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.app.api.v1.request.ProcessStepCreateRequest;
import com.meridian.substrate.common.domain.enums.ProcessStepExecutionMode;
import com.meridian.substrate.common.domain.enums.StepType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Ported homepoc phase1 test (dev-profile convention; see PhaseStateCrudTest).
// The update/delete leg is a substrate addition so the PATCH/DELETE wiring of
// the nested step routes is pinned, not just create/list.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class ProcessStepCrudTest extends Phase1StageASupport {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void createAndListProcessSteps() throws Exception {
        UUID domainId = createDomain();
        UUID processId = createProcess(domainId);

        ProcessStepCreateRequest step = new ProcessStepCreateRequest(
                0, "Intake review", "desc",
                StepType.HUMAN, ProcessStepExecutionMode.MANUAL,
                true, "rule-based", null, null, 15);

        mvc.perform(post("/api/v1/processes/{processId}/steps", processId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(step)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name", is("Intake review")))
                .andExpect(jsonPath("$.data.sequenceOrder", is(0)))
                .andExpect(jsonPath("$.data.automationCandidate", is(true)));

        mvc.perform(get("/api/v1/processes/{processId}/steps", processId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].processId", is(processId.toString())));
    }

    @Test
    void updateAndDeleteProcessStep() throws Exception {
        UUID domainId = createDomain();
        UUID processId = createProcess(domainId);

        ProcessStepCreateRequest step = new ProcessStepCreateRequest(
                null, "Original name", null,
                StepType.HUMAN, ProcessStepExecutionMode.MANUAL,
                null, null, null, null, null);

        String body = mvc.perform(post("/api/v1/processes/{processId}/steps", processId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(step)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String stepId = json.readTree(body).path("data").path("id").asText();

        // Partial update: only the name changes; null fields stay untouched.
        ProcessStepCreateRequest patchReq = new ProcessStepCreateRequest(
                null, "Renamed step", null, null, null, null, null, null, null, 30);

        mvc.perform(patch("/api/v1/processes/{processId}/steps/{stepId}", processId, stepId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(patchReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name", is("Renamed step")))
                .andExpect(jsonPath("$.data.stepType", is("HUMAN")))
                .andExpect(jsonPath("$.data.estimatedMinutes", is(30)));

        mvc.perform(delete("/api/v1/processes/{processId}/steps/{stepId}", processId, stepId))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/processes/{processId}/steps", processId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }
}
