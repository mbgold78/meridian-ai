package com.meridian.substrate.app.phase2b1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.app.api.v1.request.CapabilityCreateRequest;
import com.meridian.substrate.app.api.v1.request.CapabilitySequenceRequest;
import com.meridian.substrate.common.domain.Capability;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.domain.enums.CapabilityStatus;
import com.meridian.substrate.common.repo.CapabilityRepository;
import com.meridian.substrate.common.repo.DomainRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reorder-within-parent cascade (ported homepoc phase2b1 suite; substrate
 * dev profile / H2-PG-mode).
 *
 * <p>Seeds five siblings under one parent with sequence 0..4, then
 * exercises two distinct reorder gestures that together prove the
 * cascade:
 *
 * <ol>
 *   <li><b>Front-load move</b>: position 4 → 0. Positions 0..3 must each
 *       shift right by one (now 1..4). Front-load touches every sibling
 *       and is the harshest cascade case.</li>
 *   <li><b>Middle-to-middle move</b>: position 2 → 1 within the post-state.
 *       Only the row at position 1 should swap with position 2; untouched
 *       positions (0, 3, 4) must not change. Proves the cascade doesn't
 *       over-write.</li>
 * </ol>
 *
 * <p>A third test covers the clamp behavior on an out-of-range target.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class CapabilityReorderTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DomainRepository domainRepository;
    @Autowired CapabilityRepository capabilityRepository;

    @Test
    void frontLoadMove_cascadesEverySiblingRightByOne() throws Exception {
        UUID domainId = createDomain();
        UUID parentId = createCapability(domainId, null, "root", "Root").getId();
        List<UUID> children = List.of(
                createCapability(domainId, parentId, "child-a", "Child A").getId(),
                createCapability(domainId, parentId, "child-b", "Child B").getId(),
                createCapability(domainId, parentId, "child-c", "Child C").getId(),
                createCapability(domainId, parentId, "child-d", "Child D").getId(),
                createCapability(domainId, parentId, "child-e", "Child E").getId());

        // Sanity: created-in-order → sequence 0..4.
        assertSequences(children, List.of(0, 1, 2, 3, 4));

        // Move the last child (sequence 4) to the front.
        reorder(children.get(4), 0);

        // Expected after cascade: E is 0, A..D each shifted right to 1..4.
        assertThat(capabilityRepository.findById(children.get(4)).orElseThrow().getSequenceOrder())
                .as("moved capability lands at the clamped target position")
                .isEqualTo(0);
        assertSequences(
                List.of(children.get(0), children.get(1), children.get(2), children.get(3)),
                List.of(1, 2, 3, 4));
    }

    @Test
    void middleToMiddleMove_onlyTouchesTwoPositions() throws Exception {
        UUID domainId = createDomain();
        UUID parentId = createCapability(domainId, null, "root2", "Root 2").getId();
        UUID a = createCapability(domainId, parentId, "c-a2", "A").getId();
        UUID b = createCapability(domainId, parentId, "c-b2", "B").getId();
        UUID c = createCapability(domainId, parentId, "c-c2", "C").getId();
        UUID d = createCapability(domainId, parentId, "c-d2", "D").getId();
        UUID e = createCapability(domainId, parentId, "c-e2", "E").getId();

        // Move C (position 2) to position 1.
        reorder(c, 1);

        // Expected order: A, C, B, D, E → sequences 0, 1, 2, 3, 4.
        assertThat(capabilityRepository.findById(a).orElseThrow().getSequenceOrder()).isEqualTo(0);
        assertThat(capabilityRepository.findById(c).orElseThrow().getSequenceOrder()).isEqualTo(1);
        assertThat(capabilityRepository.findById(b).orElseThrow().getSequenceOrder()).isEqualTo(2);
        assertThat(capabilityRepository.findById(d).orElseThrow().getSequenceOrder())
                .as("D must be untouched — cascade does not over-write positions past the move window")
                .isEqualTo(3);
        assertThat(capabilityRepository.findById(e).orElseThrow().getSequenceOrder())
                .as("E must be untouched")
                .isEqualTo(4);
    }

    @Test
    void outOfRangeTarget_isClampedToValidBounds() throws Exception {
        UUID domainId = createDomain();
        UUID parentId = createCapability(domainId, null, "root3", "Root 3").getId();
        UUID a = createCapability(domainId, parentId, "c-a3", "A3").getId();
        UUID b = createCapability(domainId, parentId, "c-b3", "B3").getId();
        UUID c = createCapability(domainId, parentId, "c-c3", "C3").getId();

        // Target >> last index → service clamps to (size - 1).
        reorder(a, 99);

        // Expected: B, C, A with sequences 0, 1, 2.
        assertThat(capabilityRepository.findById(b).orElseThrow().getSequenceOrder()).isEqualTo(0);
        assertThat(capabilityRepository.findById(c).orElseThrow().getSequenceOrder()).isEqualTo(1);
        assertThat(capabilityRepository.findById(a).orElseThrow().getSequenceOrder()).isEqualTo(2);
    }

    // ---- helpers -----------------------------------------------------------

    private void assertSequences(List<UUID> ids, List<Integer> expected) {
        List<Integer> actual = ids.stream()
                .map(id -> capabilityRepository.findById(id).orElseThrow().getSequenceOrder())
                .collect(Collectors.toList());
        assertThat(actual).isEqualTo(expected);
    }

    private void reorder(UUID capabilityId, int newSequence) throws Exception {
        mvc.perform(patch("/api/v1/capabilities/{id}/sequence", capabilityId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(new CapabilitySequenceRequest(newSequence))))
                .andExpect(status().isOk());
    }

    private Capability createCapability(UUID domainId, UUID parentId, String name, String displayName) throws Exception {
        CapabilityCreateRequest req = new CapabilityCreateRequest(
                domainId, parentId, name, displayName, null,
                parentId == null ? 0 : 1, CapabilityStatus.ACTIVE, false);
        MvcResult r = mvc.perform(post("/api/v1/capabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(req)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = json.readTree(r.getResponse().getContentAsByteArray());
        UUID id = UUID.fromString(body.path("data").path("id").asText());
        return capabilityRepository.findById(id).orElseThrow();
    }

    private UUID createDomain() {
        Domain d = new Domain();
        d.setName("Phase2B1 reorder " + UUID.randomUUID());
        d.setCreatedBy("test");
        return domainRepository.save(d).getId();
    }
}
