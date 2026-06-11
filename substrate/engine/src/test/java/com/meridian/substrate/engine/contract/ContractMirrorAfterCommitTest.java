package com.meridian.substrate.engine.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.DesignDecision;
import com.meridian.substrate.common.domain.enums.ContractStatus;
import com.meridian.substrate.common.repo.DesignDecisionRepository;
import com.meridian.substrate.engine.decision.log.DecisionLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Transaction posture of the ADR-002 D14 mirror (the documented
 * act-then-anchor deviation — see
 * {@link DecisionLogContractAccountabilityMirror} javadoc): the anchor is
 * appended via an {@code afterCommit} synchronization in its own
 * {@code REQUIRES_NEW} transaction, so
 *
 * <ul>
 *   <li>a ROLLED-BACK business transaction leaves NO anchor (an anchor for an
 *       act that never happened would be the worse audit artifact), and</li>
 *   <li>a COMMITTED transaction's anchor is durable and the domain chain
 *       verifies green — sequential anchors link prev→current.</li>
 * </ul>
 *
 * <p>H2 fast lane; the §14.4 Testcontainers suite re-proves chain integrity
 * (pinned vectors + concurrent appends) on real Postgres in CI.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:contract-mirror;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ContractMirrorAfterCommitTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-12T00:00:00Z");

    @Autowired private DesignDecisionRepository designDecisionRepository;
    @Autowired private PlatformTransactionManager txManager;

    private DecisionLogService decisionLogService;
    private DecisionLogContractAccountabilityMirror mirror;
    private TransactionTemplate tx;
    private UUID domainId;

    @BeforeEach
    void setUp() {
        decisionLogService = new DecisionLogService(
                designDecisionRepository, new ObjectMapper(), txManager);
        mirror = new DecisionLogContractAccountabilityMirror(
                decisionLogService, Clock.fixed(FIXED_NOW, ZoneOffset.UTC), new ObjectMapper());
        tx = new TransactionTemplate(txManager);
        domainId = UUID.randomUUID();
    }

    @Test
    void committedTransaction_appendsAnchorAndChainVerifies() {
        Contract contract = contract("mirror-commit");

        tx.executeWithoutResult(status -> mirror.onActivated(contract));

        List<DesignDecision> anchors = decisionLogService.getDecisionLog(domainId);
        assertThat(anchors).hasSize(1);
        assertThat(anchors.get(0).getPreviousHash()).isEqualTo("GENESIS");
        assertThat(anchors.get(0).getTitle())
                .isEqualTo("contract:mirror-commit:CONTRACT_ACTIVATED");
        assertThat(decisionLogService.verifyChain(domainId).valid()).isTrue();

        // A second anchor (decline) links to the first — same domain chain.
        tx.executeWithoutResult(status -> mirror.onDeclined(contract));
        List<DesignDecision> chained = decisionLogService.getDecisionLog(domainId);
        assertThat(chained).hasSize(2);
        assertThat(chained.get(1).getPreviousHash())
                .isEqualTo(chained.get(0).getCurrentHash());
        assertThat(decisionLogService.verifyChain(domainId).valid()).isTrue();
    }

    @Test
    void rolledBackTransaction_leavesNoAnchor() {
        Contract contract = contract("mirror-rollback");

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            mirror.onActivated(contract);
            throw new IllegalStateException("business mutation failed after mirror call");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(decisionLogService.getDecisionLog(domainId))
                .as("act-then-anchor: no anchor may exist for a rolled-back act")
                .isEmpty();
    }

    private Contract contract(String key) {
        Contract c = new Contract();
        c.setId(UUID.randomUUID());
        c.setDomainId(domainId);
        c.setContractKey(key);
        c.setContractType("cross-portfolio-opportunity");
        c.setTitle("Mirror posture test contract");
        c.setStatus(ContractStatus.ACTIVE);
        c.setProposedAt(FIXED_NOW);
        c.setProposedBy("mirror-test");
        return c;
    }
}
