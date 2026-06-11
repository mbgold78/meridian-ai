package com.meridian.substrate.app.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.app.demo.AgentDemoSeeder;
import com.meridian.substrate.app.demo.SeedContext;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.domain.enums.AgentEventType;
import com.meridian.substrate.common.domain.enums.AgentOperationalMode;
import com.meridian.substrate.common.domain.enums.AgentWireStatus;
import com.meridian.substrate.common.repo.AgentRegistrationRepository;
import com.meridian.substrate.common.repo.AgentStatusEventRepository;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.engine.agent.AgentQueryService;
import com.meridian.substrate.engine.agent.read.AgentFleetRow;
import com.meridian.substrate.engine.agent.read.AgentSignals;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §15 DemoPack seed vectors (engine-spec agents.md §15/§16.2 #3; ADR-003
 * D18): the pinned Curasync 5-agent canonical vector (modes, wire statuses,
 * event counts, VERBATIM reasons/activity) and per-subsidiary stat-strip
 * parity across ALL NINE fixture files (22 subsidiaries, 70 agents).
 *
 * <p>Parity oracle: {@code demopack/beacon-agents.json}, generated VERBATIM
 * from the homepoc fixture truth
 * ({@code meridian-homepoc/frontend/src/demo/beacon/subsidiaries/*.ts},
 * copied 2026-06-11 via {@code frontend/scripts/extract-beacon-agents.ts}).
 * Aggregate totals are ADDITIONALLY hardcoded from the fixture files so a
 * corrupted/regenerated JSON cannot silently weaken the parity check:
 * 22 subsidiaries; 70 agents = 45 live + 15 shadow + 8 intervention-required
 * + 2 paused.
 *
 * <p>The seeder drives the REAL lifecycle services (never row inserts) — a
 * green run here is also a standing integration test of deploy → review →
 * promote → pause / intervention-open under a stepping fixed clock.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Transactional
class AgentDemoSeederTest {

    // Hardcoded fixture aggregates (healthcare/government/insurance/
    // instrumentation/financial/legal/logistics/education/creative .ts,
    // copied 2026-06-11) — the anti-drift pin for the JSON oracle.
    private static final int FIXTURE_SUBSIDIARIES = 22;
    private static final int FIXTURE_AGENTS = 70;
    private static final int FIXTURE_LIVE = 45;
    private static final int FIXTURE_SHADOW = 15;
    private static final int FIXTURE_INTERVENTION = 8;
    private static final int FIXTURE_PAUSED = 2;

    @Autowired AgentDemoSeeder seeder;
    @Autowired DomainRepository domains;
    @Autowired AgentRegistrationRepository registrations;
    @Autowired AgentStatusEventRepository events;
    @Autowired AgentQueryService queryService;
    @Autowired ObjectMapper json;

    @Test
    void seedsTheCuranyncCanonicalVectorAndAllNineFilesWithStatStripParity() throws Exception {
        seeder.seedBulk(new SeedContext());

        JsonNode pack = loadPack();
        assertThat(pack.get("subsidiaries")).hasSize(FIXTURE_SUBSIDIARIES);

        // ---- per-subsidiary satellite domains + stat-strip parity (ALL nine files)
        int agentsTotal = 0;
        int live = 0;
        int shadow = 0;
        int intervention = 0;
        int paused = 0;
        for (JsonNode sub : pack.get("subsidiaries")) {
            String name = sub.get("name").asText();
            Domain domain = domains.findAll().stream()
                    .filter(d -> name.equals(d.getName()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("satellite domain missing: " + name));
            assertThat(domain.isDemo()).as("%s isDemo", name).isTrue();
            assertThat(domain.getMetadata().get("beaconSubsidiary").asBoolean()).isTrue();
            assertThat(domain.getMetadata().get("beaconSlug").asText())
                    .isEqualTo(sub.get("slug").asText());

            AgentSignals signals = queryService.signals(domain.getId());
            int expectedTotal = sub.get("agents").size();
            long expectedIntervention = countByStatus(sub, "intervention-required");
            // OperationsTab strip: "Agents active" counts the whole fleet;
            // "Agents in intervention" counts the destructive pill (§13.1).
            assertThat(signals.agentsTotal()).as("%s agentsTotal", name).isEqualTo(expectedTotal);
            assertThat(signals.agentsInterventionRequired())
                    .as("%s agentsInterventionRequired", name).isEqualTo(expectedIntervention);
            assertThat(signals.agentsLive()).as("%s live", name)
                    .isEqualTo(countByStatus(sub, "live"));
            assertThat(signals.agentsShadow()).as("%s shadow", name)
                    .isEqualTo(countByStatus(sub, "shadow"));
            assertThat(signals.agentsPaused()).as("%s paused", name)
                    .isEqualTo(countByStatus(sub, "paused"));

            // wire-status + verbatim recentActivity/adherence parity per agent
            List<AgentFleetRow> fleet = queryService.fleet(domain.getId(), null, false);
            Map<String, AgentFleetRow> byKey = fleet.stream()
                    .collect(Collectors.toMap(AgentFleetRow::id, Function.identity()));
            for (JsonNode agent : sub.get("agents")) {
                AgentFleetRow row = byKey.get(agent.get("id").asText());
                assertThat(row).as("%s/%s seeded", name, agent.get("id").asText()).isNotNull();
                assertThat(row.status().wireValue())
                        .as("%s/%s wire status", name, row.id())
                        .isEqualTo(agent.get("status").asText());
                assertThat(row.recentActivity())
                        .as("%s/%s recentActivity (fixture verbatim via ATTESTATION sighting)",
                                name, row.id())
                        .isEqualTo(agent.get("recentActivity").asText());
                assertThat(row.adherence())
                        .as("%s/%s adherence (fixture via metric sample)", name, row.id())
                        .isEqualTo(agent.get("adherence").asDouble());
            }
            agentsTotal += expectedTotal;
            live += signals.agentsLive();
            shadow += signals.agentsShadow();
            intervention += signals.agentsInterventionRequired();
            paused += signals.agentsPaused();
        }
        assertThat(agentsTotal).isEqualTo(FIXTURE_AGENTS);
        assertThat(live).isEqualTo(FIXTURE_LIVE);
        assertThat(shadow).isEqualTo(FIXTURE_SHADOW);
        assertThat(intervention).isEqualTo(FIXTURE_INTERVENTION);
        assertThat(paused).isEqualTo(FIXTURE_PAUSED);

        // ---- the §15 pinned Curasync vector (all four wire statuses)
        UUID curasync = domainByName("Curasync Clinical Systems").getId();
        assertVector(curasync, "a-pre-visit", AgentOperationalMode.LIVE,
                AgentWireStatus.LIVE, 2, AgentEventType.PROMOTED);
        assertVector(curasync, "a-eligibility", AgentOperationalMode.LIVE,
                AgentWireStatus.LIVE, 2, AgentEventType.PROMOTED);
        assertVector(curasync, "a-coding", AgentOperationalMode.SHADOW,
                AgentWireStatus.SHADOW, 1, AgentEventType.DEPLOYED);
        // two-dimensional model: in intervention AND STILL LIVE underneath
        assertVector(curasync, "a-noshow", AgentOperationalMode.LIVE,
                AgentWireStatus.INTERVENTION_REQUIRED, 3, AgentEventType.INTERVENTION_OPENED);
        assertVector(curasync, "a-msg-router", AgentOperationalMode.PAUSED,
                AgentWireStatus.PAUSED, 3, AgentEventType.PAUSED);

        AgentRegistration router = registrations
                .findByDomainIdAndAgentKey(curasync, "a-msg-router").orElseThrow();
        // fixture provenance verbatim (healthcare.ts): deployedAt 2025-12-08
        assertThat(router.getDeployedAt()).isEqualTo(Instant.parse("2025-12-08T00:00:00Z"));
        assertThat(router.getDeployedByLabel()).isEqualTo("Curasync Front Office");
        assertThat(router.getModeBeforePause()).isEqualTo(AgentOperationalMode.LIVE);
        // RECORDED_INTENT pause: pending confirmation, fixture pause date honored
        assertThat(router.getControlPendingVerb()).isEqualTo("pause");
        assertThat(router.getControlRequestedAt())
                .isEqualTo(Instant.parse("2026-04-19T12:00:00Z"));
        assertThat(router.getControlConfirmedAt()).isNull();
        // pause REASON is the fixture string verbatim (§15)
        AgentRegistration noshow = registrations
                .findByDomainIdAndAgentKey(curasync, "a-noshow").orElseThrow();
        assertThat(events.findByAgentRegistrationIdOrderByEventSeqAsc(router.getId()))
                .filteredOn(e -> e.getEventType() == AgentEventType.PAUSED)
                .singleElement()
                .satisfies(e -> assertThat(e.getReason())
                        .isEqualTo("Paused 2026-04-19 pending Compliance review of PHI-routing change"));
        assertThat(events.findByAgentRegistrationIdOrderByEventSeqAsc(noshow.getId()))
                .filteredOn(e -> e.getEventType() == AgentEventType.INTERVENTION_OPENED)
                .singleElement()
                .satisfies(e -> assertThat(e.getReason())
                        .isEqualTo("Drift detected — false-positive rate up 9 pts"));
        // synthesized provenance is FLAGGED fiction; polished Curasync rows are NOT
        assertThat(router.getNotes()).isNull();
        AgentRegistration unpolished = registrations.findByDomainIdAndAgentKey(
                domainByName("RevSync Health Financial").getId(), "a-denial-class").orElseThrow();
        assertThat(unpolished.getNotes()).startsWith("SEED-FICTION:");

        // ---- idempotent re-entry: keyed (domain, agent_key) — no duplicates, no new events
        long registrationsBefore = registrations.count();
        long domainsBefore = domains.count();
        seeder.seedBulk(new SeedContext());
        assertThat(registrations.count()).isEqualTo(registrationsBefore);
        assertThat(domains.count()).isEqualTo(domainsBefore);
        assertThat(events.findByAgentRegistrationIdOrderByEventSeqAsc(router.getId())).hasSize(3);
    }

    // ------------------------------------------------------------- helpers

    private void assertVector(UUID domainId, String agentKey, AgentOperationalMode mode,
                              AgentWireStatus wireStatus, int eventCount,
                              AgentEventType lastEvent) {
        AgentRegistration reg = registrations.findByDomainIdAndAgentKey(domainId, agentKey)
                .orElseThrow(() -> new AssertionError("missing seeded agent: " + agentKey));
        assertThat(reg.getOperationalMode()).as("%s mode", agentKey).isEqualTo(mode);
        assertThat(AgentWireStatus.project(reg.getOperationalMode(), reg.getOpenInterventionCount()))
                .as("%s wire status", agentKey).isEqualTo(wireStatus);
        var eventRows = events.findByAgentRegistrationIdOrderByEventSeqAsc(reg.getId());
        assertThat(eventRows).as("%s event count", agentKey).hasSize(eventCount);
        assertThat(eventRows.get(eventRows.size() - 1).getEventType())
                .as("%s last event", agentKey).isEqualTo(lastEvent);
        assertThat(reg.getEventSeq()).as("%s fencing watermark (I10)", agentKey)
                .isEqualTo(eventCount);
    }

    private Domain domainByName(String name) {
        return domains.findAll().stream().filter(d -> name.equals(d.getName()))
                .findFirst().orElseThrow();
    }

    private static long countByStatus(JsonNode sub, String status) {
        long n = 0;
        for (JsonNode agent : sub.get("agents")) {
            if (status.equals(agent.get("status").asText())) {
                n++;
            }
        }
        return n;
    }

    private JsonNode loadPack() throws Exception {
        try (InputStream is = new ClassPathResource("demopack/beacon-agents.json").getInputStream()) {
            return json.readTree(is);
        }
    }
}
