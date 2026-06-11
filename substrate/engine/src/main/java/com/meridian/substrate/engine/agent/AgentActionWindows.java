package com.meridian.substrate.engine.agent;

import com.meridian.substrate.common.domain.AgentRateCounter;
import com.meridian.substrate.common.repo.AgentRateCounterRepository;
import com.meridian.substrate.engine.contract.gateway.Ungoverned;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Window-row provisioning for the DB-backed ACTIONS_PER_WINDOW counters
 * (engine-spec agents.md §5.5/§8.3 #4; ADR-003 D12). The row is created at
 * {@code action_count=0} in {@code REQUIRES_NEW} so a racing first-caller's
 * unique violation ({@code uq_arc_window}) poisons only this inner
 * transaction — never the governed business transaction the gate is
 * evaluating inside. The increments themselves stay atomic in-database
 * UPDATEs in the CHECK transaction ({@code AgentRateCounterRepository
 * .incrementWindow}) — restart-safe and multi-instance-safe, unlike
 * CostTracker's in-memory atomics (the S12 lesson, applied).
 */
@Service
public class AgentActionWindows {

    private static final Logger log = LoggerFactory.getLogger(AgentActionWindows.class);

    private final AgentRateCounterRepository rateCounterRepository;

    public AgentActionWindows(AgentRateCounterRepository rateCounterRepository) {
        this.rateCounterRepository = rateCounterRepository;
    }

    /**
     * Ensures the {@code (agent, window_start)} row exists; loser of the
     * insert race treats the violation as success (the row exists either
     * way). Committed immediately — a zero-count window row is bookkeeping,
     * not an action.
     */
    @Ungoverned(reason = "rate-counter window provisioning for the §8.3 #4 budget check —"
            + " gateway-internal bookkeeping in REQUIRES_NEW, not a lifecycle verb or ADR D2 PEP"
            + " (engine-spec agents.md §5.5; ADR-003 D12)")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureWindowRow(UUID agentRegistrationId, Instant windowStart) {
        if (rateCounterRepository
                .findByAgentRegistrationIdAndWindowStart(agentRegistrationId, windowStart)
                .isPresent()) {
            return;
        }
        AgentRateCounter counter = new AgentRateCounter();
        counter.setAgentRegistrationId(agentRegistrationId);
        counter.setWindowStart(windowStart);
        counter.setActionCount(0);
        try {
            rateCounterRepository.saveAndFlush(counter);
        } catch (DataIntegrityViolationException raced) {
            // uq_arc_window backstop — a concurrent caller created the row.
            log.debug("Rate-counter window already provisioned [agent={}, window={}]",
                    agentRegistrationId, windowStart);
        }
    }
}
