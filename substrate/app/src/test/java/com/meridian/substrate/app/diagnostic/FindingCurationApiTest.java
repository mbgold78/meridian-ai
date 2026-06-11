package com.meridian.substrate.app.diagnostic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.AiCurationEvent;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.domain.Finding;
import com.meridian.substrate.common.domain.enums.CurationStatus;
import com.meridian.substrate.common.domain.enums.CurationSubjectKind;
import com.meridian.substrate.common.domain.enums.FindingStatus;
import com.meridian.substrate.common.domain.enums.FindingType;
import com.meridian.substrate.common.domain.enums.Severity;
import com.meridian.substrate.common.repo.AiCurationEventRepository;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.common.repo.FindingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Diagnostic test plan #7, REST half (engine-spec diagnostic.md §9/§10.1) +
 * the curation slice of #17 (never-error-colored):
 *
 * <ul>
 *   <li>REJECT without rationale → 400 {@code RATIONALE_REQUIRED}
 *       (field-addressed); a well-formed REJECT → <b>200</b> with empty
 *       {@code errors} — rejection is a product outcome;</li>
 *   <li>idempotency replay → original 200, exactly one event row;</li>
 *   <li>ACCEPT-after-REJECT projects ACCEPTED;</li>
 *   <li>default list filter excludes REJECTED/SUPERSEDED while NULL fixture
 *       rows are UNAFFECTED (DiagnosticSeeder demo parity pinned);
 *       {@code includeRejected=true} restores; explicit
 *       {@code curationStatus=}/{@code aiGenerated=} filters.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "substrate.llm.provider=mock",
        "substrate.diagnostic.sync=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class FindingCurationApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired DomainRepository domainRepository;
    @Autowired FindingRepository findingRepository;
    @Autowired AiCurationEventRepository eventRepository;

    private UUID domainId;

    @BeforeEach
    void fixture() {
        Domain domain = new Domain();
        domain.setName("curation-api " + UUID.randomUUID());
        domain.setCreatedBy("test");
        domainId = domainRepository.save(domain).getId();
    }

    // ------------------------------------------------ reject: 400 vs 200

    @Test
    void rejectWithoutRationale_is400RationaleRequired() throws Exception {
        Finding finding = finding(CurationStatus.PENDING_REVIEW, true);
        ObjectNode body = mapper.createObjectNode().put("idempotencyKey", "k-1");

        mvc.perform(post("/api/v1/findings/{id}/reject", finding.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code", is("RATIONALE_REQUIRED")))
                .andExpect(jsonPath("$.errors[0].field", is("rationale")));

        assertThat(eventsOf(finding.getId())).isEmpty();
    }

    @Test
    void reject_returns200WithEmptyErrors_neverErrorColored() throws Exception {
        Finding finding = finding(CurationStatus.PENDING_REVIEW, true);

        mvc.perform(post("/api/v1/findings/{id}/reject", finding.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rejectBody("unsupported by evidence", "k-1"))
                        .header("X-Persona-Label", "Avery Operator"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.curationStatus", is("REJECTED")))
                .andExpect(jsonPath("$.data.curatedByLabel", is("Avery Operator")))
                .andExpect(jsonPath("$.data.replayed", is(false)));

        Finding reloaded = findingRepository.findById(finding.getId()).orElseThrow();
        assertThat(reloaded.getCurationStatus()).isEqualTo(CurationStatus.REJECTED);
        assertThat(reloaded.getCuratedByLabel()).isEqualTo("Avery Operator");
    }

    // -------------------------------------------- accept + recourse + replay

    @Test
    void acceptAfterReject_projectsAccepted_historyPreserved() throws Exception {
        Finding finding = finding(CurationStatus.PENDING_REVIEW, true);
        mvc.perform(post("/api/v1/findings/{id}/reject", finding.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rejectBody("mis-click", "k-reject")))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/findings/{id}/accept", finding.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptBody("k-accept")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.curationStatus", is("ACCEPTED")));

        assertThat(findingRepository.findById(finding.getId()).orElseThrow().getCurationStatus())
                .isEqualTo(CurationStatus.ACCEPTED);
        assertThat(eventsOf(finding.getId())).hasSize(2); // append-only recourse
    }

    @Test
    void idempotencyReplay_returnsOriginal200_exactlyOneEvent() throws Exception {
        Finding finding = finding(CurationStatus.PENDING_REVIEW, true);

        MvcResult first = mvc.perform(post("/api/v1/findings/{id}/accept", finding.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptBody("k-same")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed", is(false)))
                .andReturn();
        String originalEventId = mapper.readTree(first.getResponse().getContentAsString())
                .at("/data/eventId").asText();

        mvc.perform(post("/api/v1/findings/{id}/accept", finding.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptBody("k-same")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.replayed", is(true)))
                .andExpect(jsonPath("$.data.eventId", is(originalEventId)));

        assertThat(eventsOf(finding.getId())).hasSize(1);
    }

    // ----------------------------------------------------------- conflicts

    @Test
    void acceptOnFixtureNullRow_is409CurationConflict() throws Exception {
        Finding fixture = finding(null, false); // DiagnosticSeeder-shaped row

        mvc.perform(post("/api/v1/findings/{id}/accept", fixture.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptBody("k-1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code", is("CURATION_CONFLICT")));
    }

    @Test
    void acceptUnknownFinding_is404() throws Exception {
        mvc.perform(post("/api/v1/findings/{id}/accept", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptBody("k-1")))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------- §9 list-filter defaults

    @Test
    void defaultListFilter_excludesRejectedAndSuperseded_nullRowsUnaffected() throws Exception {
        Finding fixture = finding(null, false);                          // NULL — unaffected
        Finding pending = finding(CurationStatus.PENDING_REVIEW, true);
        Finding accepted = finding(CurationStatus.ACCEPTED, true);
        Finding rejected = finding(CurationStatus.REJECTED, true);
        Finding superseded = finding(CurationStatus.SUPERSEDED, true);

        // DEFAULT: curation_status NOT IN (REJECTED, SUPERSEDED) OR NULL
        mvc.perform(get("/api/v1/ecosystem/findings").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[*].id", containsInAnyOrder(
                        fixture.getId().toString(),
                        pending.getId().toString(),
                        accepted.getId().toString())));

        // includeRejected=true restores the excluded rows
        mvc.perform(get("/api/v1/ecosystem/findings")
                        .param("domainId", domainId.toString())
                        .param("includeRejected", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(5)));

        // explicit curationStatus= overrides the default (asking shows REJECTED)
        mvc.perform(get("/api/v1/ecosystem/findings")
                        .param("domainId", domainId.toString())
                        .param("curationStatus", "REJECTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id", is(rejected.getId().toString())));

        // aiGenerated=false → operator/fixture rows only
        mvc.perform(get("/api/v1/ecosystem/findings")
                        .param("domainId", domainId.toString())
                        .param("aiGenerated", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id", is(fixture.getId().toString())));

        // combined: AI rows under the default filter
        mvc.perform(get("/api/v1/ecosystem/findings")
                        .param("domainId", domainId.toString())
                        .param("aiGenerated", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].id", containsInAnyOrder(
                        pending.getId().toString(),
                        accepted.getId().toString())));
        // the SUPERSEDED row also stayed out of the curationStatus-less views above
        assertThat(superseded.getId()).isNotNull();
    }

    // --------------------------------------------------------------- helpers

    private String acceptBody(String key) {
        return mapper.createObjectNode().put("idempotencyKey", key).toString();
    }

    private String rejectBody(String rationale, String key) {
        return mapper.createObjectNode()
                .put("rationale", rationale)
                .put("idempotencyKey", key)
                .toString();
    }

    private Finding finding(CurationStatus status, boolean aiGenerated) {
        Finding finding = new Finding();
        finding.setDomainId(domainId);
        finding.setFindingType(FindingType.DATA_QUALITY);
        finding.setSeverity(Severity.MEDIUM);
        finding.setStatus(FindingStatus.OPEN);
        finding.setTitle("Subject " + UUID.randomUUID());
        finding.setDescription("Curation API test finding.");
        finding.setAiGenerated(aiGenerated);
        finding.setCurationStatus(status);
        finding.setCreatedBy(aiGenerated ? "diagnostic-engine" : "seed");
        return findingRepository.save(finding);
    }

    private List<AiCurationEvent> eventsOf(UUID findingId) {
        return eventRepository.findBySubjectKindAndSubjectIdOrderByCreatedAtAsc(
                CurationSubjectKind.FINDING, findingId);
    }
}
