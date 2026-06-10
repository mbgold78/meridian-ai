package com.meridian.substrate.app.phase2b1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.app.branding.BrandingConfigUpsertRequest;
import com.meridian.substrate.app.branding.BrandingDefaults;
import com.meridian.substrate.common.domain.BrandingConfig;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.domain.enums.BrandingConfigStatus;
import com.meridian.substrate.common.repo.BrandingConfigRepository;
import com.meridian.substrate.common.repo.DomainRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@code /api/v1/branding/configs} — ported homepoc
 * phase2b1 suite (BrandingConfig CRUD + admin gate + active-resolution),
 * running on the substrate dev profile (H2-PG-mode + Flyway V1/V2;
 * H2-green is not the Postgres acceptance gate — port-map §4.4).
 *
 * <p>Three orthogonal seams:
 * <ol>
 *   <li>Create + read round-trip preserves color tokens, terminology, and
 *       enforces the admin gate (POST without {@code X-Persona-Level: ADMIN}
 *       returns 403).</li>
 *   <li>{@code GET /configs/active?domainId=...} returns a transient default
 *       when no ACTIVE row exists, and never persists one as a side effect.</li>
 *   <li>{@code POST /configs/{id}/activate} demotes any prior ACTIVE row to
 *       DRAFT before promoting the target — single-row invariant preserved.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class BrandingConfigCrudTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DomainRepository domainRepository;
    @Autowired BrandingConfigRepository brandingRepository;

    @Test
    void create_requiresAdminPersona_andRoundTripsColorAndTerminology() throws Exception {
        UUID domainId = createDomain("admin-auth-roundtrip");
        BrandingConfigUpsertRequest req = sampleRequest(domainId, BrandingConfigStatus.DRAFT);

        // Without X-Persona-Level: ADMIN → 403
        mvc.perform(post("/api/v1/branding/configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(req)))
                .andExpect(status().isForbidden());

        // With admin header → 201, full round-trip
        MvcResult created = mvc.perform(post("/api/v1/branding/configs")
                        .header("X-Persona-Level", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(req)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = json.readTree(created.getResponse().getContentAsByteArray()).path("data");
        UUID id = UUID.fromString(body.path("id").asText());

        assertThat(body.path("appDisplayName").asText()).isEqualTo("Acme Risk");
        assertThat(body.path("colorTokensLight").path("accent").asText()).isEqualTo("#aa0044");
        assertThat(body.path("terminologyOverrides").path("finding").path("singular").asText()).isEqualTo("Issue");

        // GET single
        mvc.perform(get("/api/v1/branding/configs/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id.toString()));

        // List by domain
        mvc.perform(get("/api/v1/branding/configs").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(id.toString()));
    }

    @Test
    void getActive_returnsTransientDefault_whenNoActiveExists_withoutPersisting() throws Exception {
        UUID domainId = createDomain("active-default");

        // Pre-condition: no rows for this domain
        assertThat(brandingRepository.findByDomainId(domainId)).isEmpty();

        MvcResult result = mvc.perform(get("/api/v1/branding/configs/active")
                        .param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = json.readTree(result.getResponse().getContentAsByteArray()).path("data");

        assertThat(data.path("appDisplayName").asText())
                .as("transient default uses the canonical Meridian display name")
                .isEqualTo(BrandingDefaults.DEFAULT_APP_DISPLAY_NAME);
        assertThat(data.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(data.path("terminologyOverrides").path("capability").path("plural").asText())
                .isEqualTo("Capabilities");

        // Critical: read must NOT persist a row as a side effect.
        assertThat(brandingRepository.findByDomainId(domainId))
                .as("GET /configs/active must not persist a row when none exists")
                .isEmpty();
    }

    @Test
    void activate_demotesPriorActiveToDraft_keepingExactlyOneActivePerDomain() throws Exception {
        UUID domainId = createDomain("activate-flip");

        UUID firstId = createConfigViaApi(domainId, BrandingConfigStatus.ACTIVE).getId();
        UUID secondId = createConfigViaApi(domainId, BrandingConfigStatus.DRAFT).getId();

        // Sanity: first is ACTIVE, second is DRAFT
        assertThat(brandingRepository.findById(firstId).orElseThrow().getStatus())
                .isEqualTo(BrandingConfigStatus.ACTIVE);
        assertThat(brandingRepository.findById(secondId).orElseThrow().getStatus())
                .isEqualTo(BrandingConfigStatus.DRAFT);

        // Promote the second
        mvc.perform(post("/api/v1/branding/configs/{id}/activate", secondId)
                        .header("X-Persona-Level", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        // Invariant: exactly one ACTIVE row in this domain, and it's the second
        BrandingConfig firstAfter  = brandingRepository.findById(firstId).orElseThrow();
        BrandingConfig secondAfter = brandingRepository.findById(secondId).orElseThrow();

        assertThat(firstAfter.getStatus())
                .as("prior ACTIVE config must demote to DRAFT atomically with the activation")
                .isEqualTo(BrandingConfigStatus.DRAFT);
        assertThat(secondAfter.getStatus()).isEqualTo(BrandingConfigStatus.ACTIVE);

        long activeCount = brandingRepository.findByDomainId(domainId).stream()
                .filter(c -> c.getStatus() == BrandingConfigStatus.ACTIVE)
                .count();
        assertThat(activeCount)
                .as("service-side demote must keep exactly one ACTIVE per domain "
                        + "(DB-level partial index unavailable on H2 — Postgres has "
                        + "uq_branding_configs_active_per_domain, V2_1)")
                .isEqualTo(1);
    }

    private BrandingConfig createConfigViaApi(UUID domainId, BrandingConfigStatus status) throws Exception {
        BrandingConfigUpsertRequest req = sampleRequest(domainId, status);
        MvcResult r = mvc.perform(post("/api/v1/branding/configs")
                        .header("X-Persona-Level", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(req)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID id = UUID.fromString(json.readTree(r.getResponse().getContentAsByteArray())
                .path("data").path("id").asText());
        return brandingRepository.findById(id).orElseThrow();
    }

    private BrandingConfigUpsertRequest sampleRequest(UUID domainId, BrandingConfigStatus status) {
        JsonNode lightTokens = json.createObjectNode().put("accent", "#aa0044").put("background", "#ffffff");
        JsonNode darkTokens  = json.createObjectNode().put("accent", "#ff5599").put("background", "#000000");
        JsonNode terminology = json.createObjectNode()
                .set("finding", json.createObjectNode().put("singular", "Issue").put("plural", "Issues"));

        return new BrandingConfigUpsertRequest(
                domainId,
                "Acme Risk",
                "Run the firm",
                lightTokens,
                darkTokens,
                "Inter, sans-serif",
                "Inter, sans-serif",
                "JetBrains Mono, monospace",
                null, null, null,
                null, null, null, null, null, null,
                terminology,
                null, null, null, null, null, null,
                null, null, null,
                null,
                status
        );
    }

    private UUID createDomain(String label) {
        Domain d = new Domain();
        d.setName("Phase2B1 " + label + " " + UUID.randomUUID());
        d.setCreatedBy("test");
        return domainRepository.save(d).getId();
    }
}
