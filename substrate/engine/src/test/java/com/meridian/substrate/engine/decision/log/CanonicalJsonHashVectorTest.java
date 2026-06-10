package com.meridian.substrate.engine.decision.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.DesignDecision;
import com.meridian.substrate.common.domain.enums.DecisionStatus;
import com.meridian.substrate.common.domain.enums.DecisionType;
import com.meridian.substrate.common.repo.DesignDecisionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the homepoc hash-chain wire format byte-for-byte (port-map §6
 * [DECISIONLOG-CHAIN] + Appendix trap #3).
 *
 * <p>The expected canonical-JSON strings and SHA-256 digests below were generated
 * by executing the EXACT algorithm of
 * {@code C:/Users/mbgol/dev/meridian-homepoc/app/src/main/java/com/meridian/homepoc/app/decision/log/DecisionLogService.java}
 * ({@code buildCanonicalJson} + {@code sha256}) standalone on Jackson 2.18.3 /
 * Java 21 (the substrate toolchain). Any change to the substrate port that alters
 * these bytes makes every existing chain unverifiable — these constants must
 * NEVER be regenerated to make a failing test pass.
 *
 * <p>Semantics pinned here:
 * <ul>
 *   <li>Top-level keys sorted alphabetically (TreeMap), compact output (no whitespace).</li>
 *   <li>Null content fields are OMITTED, except {@code previousHash} which is always present.</li>
 *   <li>Enums serialize via {@code .name()}.</li>
 *   <li>{@code domainId} serializes as its string form.</li>
 *   <li>Nested {@link com.fasterxml.jackson.databind.JsonNode} values keep INSERTION order —
 *       {@code ORDER_MAP_ENTRIES_BY_KEYS} sorts only {@code java.util.Map}s, never JsonNodes
 *       (see {@code impactSummary}/{@code metadata} vectors below, whose keys are deliberately
 *       non-alphabetical).</li>
 *   <li>Volatile fields (id, createdAt, updatedAt, version, createdBy) and {@code currentHash}
 *       are excluded from the hash input.</li>
 *   <li>SHA-256 over UTF-8 bytes, lowercase hex.</li>
 *   <li>Chain root sentinel is the literal {@code "GENESIS"}.</li>
 * </ul>
 */
class CanonicalJsonHashVectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DOMAIN_ID = "3bf350fc-e026-495c-a368-01865dce54ca";

    // ---- Pinned vector 1: full-field decision, GENESIS link -------------------------------
    private static final String EXPECTED_JSON_1 =
            "{\"affectedProcessIds\":[\"b1b2c3d4-0006-4b00-9000-000000000006\",\"b1b2c3d4-0008-4b00-9000-000000000008\"],"
            + "\"affectedSystemIds\":[\"a1b2c3d4-0001-4a00-9000-000000000001\",\"a1b2c3d4-0002-4a00-9000-000000000002\"],"
            + "\"decisionType\":\"CONSOLIDATE\","
            + "\"description\":\"Consolidate all enterprise issue tracking into IssuesPortal and decommission Archer's built-in issue tracking module. IssuesPortal will be extended with ERT-specific fields to absorb the shadow tracker. The weekly Archer-to-IssuesPortal sync (MP-006) will be eliminated once the Archer module is disabled.\","
            + "\"domainId\":\"" + DOMAIN_ID + "\","
            + "\"impactSummary\":{\"fteSaved\":1.5,\"annualSavings\":180000,\"note\":\"eliminates weekly Archer-to-IssuesPortal sync\"},"
            + "\"previousHash\":\"GENESIS\","
            + "\"rationale\":\"Three overlapping issue tracking surfaces (IssuesPortal, Archer issues module, ERT parallel tracker) create reconciliation burden and inconsistent audit trails. IssuesPortal has the strongest workflow engine and MetricStream vendor support for extensions.\","
            + "\"status\":\"APPROVED\","
            + "\"title\":\"D-001: Consolidate IssuesPortal and Archer Issues Tracking\"}";
    private static final String EXPECTED_HASH_1 =
            "7ad379964e4c420dde078d76fe06f2c83a4a94633912faefe66135f3ef6f8f17";

    // ---- Pinned vector 2: metadata present, impactSummary absent, links to vector 1 -------
    private static final String EXPECTED_JSON_2 =
            "{\"affectedProcessIds\":[\"b1b2c3d4-0003-4b00-9000-000000000003\"],"
            + "\"affectedSystemIds\":[\"a1b2c3d4-0003-4a00-9000-000000000003\",\"a1b2c3d4-0002-4a00-9000-000000000002\"],"
            + "\"decisionType\":\"RETIRE\","
            + "\"description\":\"Migrate the remaining three business lines from Piedmont Risk Register to Archer GRC and retire Piedmont. Historical data will be archived and indexed for regulatory lookup. The monthly Archer-to-Piedmont reconciliation (MP-003) and on-demand historical lookups (MP-026) will be eliminated.\","
            + "\"domainId\":\"" + DOMAIN_ID + "\","
            + "\"metadata\":{\"batchId\":\"batch-2026-001\",\"actor\":\"risk-architect-01\"},"
            + "\"previousHash\":\"" + EXPECTED_HASH_1 + "\","
            + "\"rationale\":\"Piedmont's 20-year-old SharePoint platform is a maintenance liability with stale assessments. Completing the Archer migration eliminates a 0.4 FTE reconciliation process and establishes a single RCSA source of truth.\","
            + "\"status\":\"APPROVED\","
            + "\"title\":\"D-002: Retire Piedmont Risk Register and Complete Archer Migration\"}";
    private static final String EXPECTED_HASH_2 =
            "4dbe53909ac12175671a8d56dbba9b14baeb02e5094b5d31f1f0b8bbbc8b9df7";

    // ---- Pinned vector 3: minimal fields (all optional JSON/rationale absent) -------------
    private static final String EXPECTED_JSON_3 =
            "{\"decisionType\":\"MODERNIZE\","
            + "\"description\":\"Minimal decision content.\","
            + "\"domainId\":\"" + DOMAIN_ID + "\","
            + "\"previousHash\":\"" + EXPECTED_HASH_2 + "\","
            + "\"status\":\"PROPOSED\","
            + "\"title\":\"D-003: Minimal\"}";
    private static final String EXPECTED_HASH_3 =
            "3917070a792343e1b04b504abda0c9b694461d160dbd6bd811b611e5eda3c2a4";

    private DecisionLogService service;

    @BeforeEach
    void setUp() {
        DesignDecisionRepository repository = mock(DesignDecisionRepository.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new DecisionLogService(repository, new ObjectMapper(), txManager);
    }

    @Test
    void canonicalJson_fullFieldDecision_matchesHomepocBytes() {
        DesignDecision d1 = decision1();
        d1.setPreviousHash("GENESIS");

        assertThat(service.buildCanonicalJson(d1)).isEqualTo(EXPECTED_JSON_1);
        assertThat(service.sha256(EXPECTED_JSON_1)).isEqualTo(EXPECTED_HASH_1);
    }

    @Test
    void canonicalJson_metadataDecision_matchesHomepocBytes() {
        DesignDecision d2 = decision2();
        d2.setPreviousHash(EXPECTED_HASH_1);

        assertThat(service.buildCanonicalJson(d2)).isEqualTo(EXPECTED_JSON_2);
        assertThat(service.sha256(EXPECTED_JSON_2)).isEqualTo(EXPECTED_HASH_2);
    }

    @Test
    void canonicalJson_minimalDecision_matchesHomepocBytes() {
        DesignDecision d3 = decision3();
        d3.setPreviousHash(EXPECTED_HASH_2);

        assertThat(service.buildCanonicalJson(d3)).isEqualTo(EXPECTED_JSON_3);
        assertThat(service.sha256(EXPECTED_JSON_3)).isEqualTo(EXPECTED_HASH_3);
    }

    /** The service's sha256 must agree with an independent JDK MessageDigest computation. */
    @Test
    void sha256_agreesWithIndependentDigest() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(EXPECTED_JSON_1.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        assertThat(sb.toString()).isEqualTo(EXPECTED_HASH_1);
        assertThat(service.sha256(EXPECTED_JSON_1)).isEqualTo(sb.toString());
    }

    /**
     * Full append flow over a stubbed repository reproduces the pinned chain
     * GENESIS → HASH_1 → HASH_2 → HASH_3 end-to-end.
     */
    @Test
    void appendFlow_reproducesPinnedChain() {
        DesignDecisionRepository repository = mock(DesignDecisionRepository.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(repository.save(any(DesignDecision.class))).thenAnswer(inv -> inv.getArgument(0));
        DecisionLogService chained = new DecisionLogService(repository, new ObjectMapper(), txManager);

        // Stubs return fresh mutable lists — the service sorts the repository
        // result in place (homepoc behavior; real JPA repositories return
        // mutable ArrayLists).
        when(repository.findByDomainId(UUID.fromString(DOMAIN_ID)))
                .thenAnswer(inv -> new java.util.ArrayList<DesignDecision>());
        DesignDecision d1 = chained.appendDecision(decision1());
        assertThat(d1.getPreviousHash()).isEqualTo("GENESIS");
        assertThat(d1.getCurrentHash()).isEqualTo(EXPECTED_HASH_1);

        when(repository.findByDomainId(UUID.fromString(DOMAIN_ID)))
                .thenAnswer(inv -> new java.util.ArrayList<>(List.of(d1)));
        DesignDecision d2 = chained.appendDecision(decision2());
        assertThat(d2.getPreviousHash()).isEqualTo(EXPECTED_HASH_1);
        assertThat(d2.getCurrentHash()).isEqualTo(EXPECTED_HASH_2);

        when(repository.findByDomainId(UUID.fromString(DOMAIN_ID)))
                .thenAnswer(inv -> new java.util.ArrayList<>(List.of(d1, d2)));
        DesignDecision d3 = chained.appendDecision(decision3());
        assertThat(d3.getPreviousHash()).isEqualTo(EXPECTED_HASH_2);
        assertThat(d3.getCurrentHash()).isEqualTo(EXPECTED_HASH_3);
    }

    // ------------------------------------------------------------------------------------
    // Vector fixtures — field values must stay byte-identical to the generator inputs.
    // ------------------------------------------------------------------------------------

    static DesignDecision decision1() {
        DesignDecision d = new DesignDecision();
        d.setDomainId(UUID.fromString(DOMAIN_ID));
        d.setDecisionType(DecisionType.CONSOLIDATE);
        d.setStatus(DecisionStatus.APPROVED);
        d.setTitle("D-001: Consolidate IssuesPortal and Archer Issues Tracking");
        d.setDescription("Consolidate all enterprise issue tracking into IssuesPortal and decommission Archer's built-in issue tracking module. IssuesPortal will be extended with ERT-specific fields to absorb the shadow tracker. The weekly Archer-to-IssuesPortal sync (MP-006) will be eliminated once the Archer module is disabled.");
        d.setRationale("Three overlapping issue tracking surfaces (IssuesPortal, Archer issues module, ERT parallel tracker) create reconciliation burden and inconsistent audit trails. IssuesPortal has the strongest workflow engine and MetricStream vendor support for extensions.");

        ArrayNode systems = MAPPER.createArrayNode();
        systems.add("a1b2c3d4-0001-4a00-9000-000000000001");
        systems.add("a1b2c3d4-0002-4a00-9000-000000000002");
        d.setAffectedSystemIds(systems);

        ArrayNode processes = MAPPER.createArrayNode();
        processes.add("b1b2c3d4-0006-4b00-9000-000000000006");
        processes.add("b1b2c3d4-0008-4b00-9000-000000000008");
        d.setAffectedProcessIds(processes);

        // Deliberately non-alphabetical insertion order: pins that nested JsonNodes
        // are NOT key-sorted by the canonicalizer.
        ObjectNode impact = MAPPER.createObjectNode();
        impact.put("fteSaved", 1.5);
        impact.put("annualSavings", 180000);
        impact.put("note", "eliminates weekly Archer-to-IssuesPortal sync");
        d.setImpactSummary(impact);
        return d;
    }

    static DesignDecision decision2() {
        DesignDecision d = new DesignDecision();
        d.setDomainId(UUID.fromString(DOMAIN_ID));
        d.setDecisionType(DecisionType.RETIRE);
        d.setStatus(DecisionStatus.APPROVED);
        d.setTitle("D-002: Retire Piedmont Risk Register and Complete Archer Migration");
        d.setDescription("Migrate the remaining three business lines from Piedmont Risk Register to Archer GRC and retire Piedmont. Historical data will be archived and indexed for regulatory lookup. The monthly Archer-to-Piedmont reconciliation (MP-003) and on-demand historical lookups (MP-026) will be eliminated.");
        d.setRationale("Piedmont's 20-year-old SharePoint platform is a maintenance liability with stale assessments. Completing the Archer migration eliminates a 0.4 FTE reconciliation process and establishes a single RCSA source of truth.");

        ArrayNode systems = MAPPER.createArrayNode();
        systems.add("a1b2c3d4-0003-4a00-9000-000000000003");
        systems.add("a1b2c3d4-0002-4a00-9000-000000000002");
        d.setAffectedSystemIds(systems);

        ArrayNode processes = MAPPER.createArrayNode();
        processes.add("b1b2c3d4-0003-4b00-9000-000000000003");
        d.setAffectedProcessIds(processes);

        ObjectNode metadata = MAPPER.createObjectNode();
        metadata.put("batchId", "batch-2026-001");
        metadata.put("actor", "risk-architect-01");
        d.setMetadata(metadata);
        return d;
    }

    static DesignDecision decision3() {
        DesignDecision d = new DesignDecision();
        d.setDomainId(UUID.fromString(DOMAIN_ID));
        d.setDecisionType(DecisionType.MODERNIZE);
        d.setStatus(DecisionStatus.PROPOSED);
        d.setTitle("D-003: Minimal");
        d.setDescription("Minimal decision content.");
        return d;
    }
}
