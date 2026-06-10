package com.meridian.substrate.engine.decision.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.DesignDecision;
import com.meridian.substrate.common.domain.enums.DecisionStatus;
import com.meridian.substrate.common.domain.enums.DecisionType;
import com.meridian.substrate.common.repo.DesignDecisionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Behavioral unit tests for the ported {@link DecisionLogService} (homepoc semantics:
 * GENESIS root, head linkage, verify walk, tamper detection) over a mocked repository.
 */
class DecisionLogServiceTest {

    private final UUID domainId = UUID.randomUUID();

    private DesignDecisionRepository repository;
    private DecisionLogService service;
    private final List<DesignDecision> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repository = mock(DesignDecisionRepository.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(repository.findByDomainId(domainId)).thenAnswer(inv -> new ArrayList<>(stored));
        when(repository.save(any(DesignDecision.class))).thenAnswer(inv -> {
            DesignDecision d = inv.getArgument(0);
            stored.add(d);
            return d;
        });
        service = new DecisionLogService(repository, new ObjectMapper(), txManager);
    }

    private DesignDecision decision(String title) {
        DesignDecision d = new DesignDecision();
        d.setDomainId(domainId);
        d.setDecisionType(DecisionType.CONSOLIDATE);
        d.setStatus(DecisionStatus.PROPOSED);
        d.setTitle(title);
        d.setDescription("Description for " + title);
        return d;
    }

    @Test
    void firstAppend_linksToGenesis() {
        DesignDecision saved = service.appendDecision(decision("first"));

        assertThat(saved.getPreviousHash()).isEqualTo("GENESIS");
        assertThat(saved.getCurrentHash())
                .isNotNull()
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    void secondAppend_linksToFirstDecisionsHash() {
        DesignDecision first = service.appendDecision(decision("first"));
        DesignDecision second = service.appendDecision(decision("second"));

        assertThat(second.getPreviousHash()).isEqualTo(first.getCurrentHash());
        assertThat(second.getCurrentHash()).isNotEqualTo(first.getCurrentHash());
    }

    @Test
    void verifyChain_emptyDomain_isTriviallyValid() {
        HashChainVerification result = service.verifyChain(domainId);

        assertThat(result.valid()).isTrue();
        assertThat(result.totalDecisions()).isZero();
        assertThat(result.verifiedLinks()).isZero();
        assertThat(result.firstBrokenAt()).isNull();
    }

    @Test
    void verifyChain_intactChain_isValid() {
        service.appendDecision(decision("first"));
        service.appendDecision(decision("second"));
        service.appendDecision(decision("third"));

        HashChainVerification result = service.verifyChain(domainId);

        assertThat(result.valid()).isTrue();
        assertThat(result.totalDecisions()).isEqualTo(3);
        assertThat(result.verifiedLinks()).isEqualTo(3);
    }

    @Test
    void verifyChain_detectsContentTampering() {
        service.appendDecision(decision("first"));
        service.appendDecision(decision("second"));

        // Tamper with the first decision's content after it was chained
        stored.get(0).setDescription("tampered description");

        HashChainVerification result = service.verifyChain(domainId);

        assertThat(result.valid()).isFalse();
        assertThat(result.verifiedLinks()).isZero();
        assertThat(result.firstBrokenAt()).isEqualTo(stored.get(0).getTitle()); // no id on unmanaged entity
        assertThat(result.message()).contains("Hash mismatch");
    }

    @Test
    void verifyChain_detectsBrokenLink() {
        service.appendDecision(decision("first"));
        service.appendDecision(decision("second"));

        // Break the linkage of the second decision (and recompute nothing else)
        stored.get(1).setPreviousHash("0000000000000000000000000000000000000000000000000000000000000000");

        HashChainVerification result = service.verifyChain(domainId);

        assertThat(result.valid()).isFalse();
        assertThat(result.verifiedLinks()).isEqualTo(1);
        assertThat(result.message()).contains("Chain broken");
    }

    @Test
    void getChainHead_emptyChain_isGenesis() {
        assertThat(service.getChainHead(domainId)).isEqualTo("GENESIS");
    }

    @Test
    void getChainHead_followsLatestAppend() {
        service.appendDecision(decision("first"));
        DesignDecision second = service.appendDecision(decision("second"));

        assertThat(service.getChainHead(domainId)).isEqualTo(second.getCurrentHash());
    }

    @Test
    void hashExcludesVolatileFields_currentHashNotInOwnHashInput() {
        DesignDecision saved = service.appendDecision(decision("first"));

        // Recomputing the canonical JSON after currentHash was set must yield the
        // same bytes — currentHash is excluded from its own hash input.
        String canonicalAfterSave = service.buildCanonicalJson(saved);
        assertThat(service.sha256(canonicalAfterSave)).isEqualTo(saved.getCurrentHash());
    }
}
