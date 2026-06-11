package com.meridian.substrate.engine.contract.gateway;

import com.meridian.substrate.common.domain.ContractClauseGuard;
import com.meridian.substrate.common.domain.enums.ContractStatus;
import com.meridian.substrate.common.domain.enums.GuardEffect;
import com.meridian.substrate.common.repo.ContractClauseGuardRepository;
import com.meridian.substrate.engine.contract.events.ContractActivatedEvent;
import com.meridian.substrate.engine.contract.events.ContractDeclinedEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * §4.3/§10.8 GuardCache semantics: per-domain snapshot (1 indexed read on
 * miss), in-memory action-type filtering, invalidation on contract
 * activation/decline events, and the 30 s TTL safety net.
 */
class GuardCacheTest {

    /** Settable clock so the TTL lane needs no sleeping. */
    private static final class SettableClock extends Clock {
        private Instant now = Instant.parse("2026-06-10T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }
    }

    private final ContractClauseGuardRepository guardRepo = mock(ContractClauseGuardRepository.class);
    private final SettableClock clock = new SettableClock();
    private final GuardCache cache = new GuardCache(guardRepo, clock);

    private final UUID domainId = UUID.randomUUID();
    private final UUID contractId = UUID.randomUUID();

    private ContractClauseGuard guard(String actionType) {
        ContractClauseGuard g = new ContractClauseGuard();
        g.setId(UUID.randomUUID());
        g.setContractId(contractId);
        g.setActionType(actionType);
        g.setEffect(GuardEffect.DENY);
        g.setActive(true);
        g.setDescription("guard " + actionType);
        return g;
    }

    @Test
    void secondReadIsServedFromTheSnapshot_oneIndexedReadOnMiss() {
        when(guardRepo.findActiveByDomainId(domainId))
                .thenReturn(List.of(guard("agent.promote")));

        assertThat(cache.activeGuards(domainId, "agent.promote")).hasSize(1);
        assertThat(cache.activeGuards(domainId, "agent.promote")).hasSize(1);

        verify(guardRepo, times(1)).findActiveByDomainId(domainId);
    }

    @Test
    void actionTypeFilterIsInMemoryOverThePerDomainSnapshot() {
        when(guardRepo.findActiveByDomainId(domainId))
                .thenReturn(List.of(guard("agent.promote"), guard("process.update")));

        assertThat(cache.activeGuards(domainId, "agent.promote"))
                .extracting(GuardView::actionType).containsExactly("agent.promote");
        assertThat(cache.activeGuards(domainId, "no.such.action")).isEmpty();
        verify(guardRepo, times(1)).findActiveByDomainId(domainId);   // same snapshot
    }

    @Test
    void activationEventInvalidatesTheDomain() {
        when(guardRepo.findActiveByDomainId(domainId))
                .thenReturn(List.of())
                .thenReturn(List.of(guard("agent.promote")));   // guards flipped live (§2.2)

        assertThat(cache.activeGuards(domainId, "agent.promote")).isEmpty();
        cache.onContractActivated(new ContractActivatedEvent(contractId, domainId, "key-1"));
        assertThat(cache.activeGuards(domainId, "agent.promote")).hasSize(1);

        verify(guardRepo, times(2)).findActiveByDomainId(domainId);
    }

    @Test
    void declineEventInvalidatesTheDomain() {
        when(guardRepo.findActiveByDomainId(domainId))
                .thenReturn(List.of(guard("agent.promote")))
                .thenReturn(List.of());                          // guards deactivated (§2.2)

        assertThat(cache.activeGuards(domainId, "agent.promote")).hasSize(1);
        cache.onContractDeclined(new ContractDeclinedEvent(
                contractId, domainId, "key-1", ContractStatus.ACTIVE));
        assertThat(cache.activeGuards(domainId, "agent.promote")).isEmpty();
    }

    @Test
    void ttlSafetyNetReloadsAfter30Seconds() {
        when(guardRepo.findActiveByDomainId(domainId))
                .thenReturn(List.of(guard("agent.promote")));

        cache.activeGuards(domainId, "agent.promote");
        clock.advance(Duration.ofSeconds(29));
        cache.activeGuards(domainId, "agent.promote");
        verify(guardRepo, times(1)).findActiveByDomainId(domainId);  // still fresh

        clock.advance(Duration.ofSeconds(2));                        // past the 30 s TTL
        cache.activeGuards(domainId, "agent.promote");
        verify(guardRepo, times(2)).findActiveByDomainId(domainId);
    }

    @Test
    void domainsAreIndependent() {
        UUID otherDomain = UUID.randomUUID();
        when(guardRepo.findActiveByDomainId(domainId))
                .thenReturn(List.of(guard("agent.promote")));
        when(guardRepo.findActiveByDomainId(otherDomain)).thenReturn(List.of());

        assertThat(cache.activeGuards(domainId, "agent.promote")).hasSize(1);
        assertThat(cache.activeGuards(otherDomain, "agent.promote")).isEmpty();

        cache.invalidate(otherDomain);                               // no effect on domainId
        assertThat(cache.activeGuards(domainId, "agent.promote")).hasSize(1);
        verify(guardRepo, times(1)).findActiveByDomainId(domainId);
    }
}
