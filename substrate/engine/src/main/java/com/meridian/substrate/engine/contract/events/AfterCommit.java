package com.meridian.substrate.engine.contract.events;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Spec §9: {@code engine.contract.events} are published AFTER COMMIT
 * (TransactionSynchronization) so listeners never observe an uncommitted
 * transition. Shared by the registry (3a precedent — same logic inlined
 * there) and the 3b monitor/breach/arming services.
 */
public final class AfterCommit {

    private AfterCommit() {
    }

    /**
     * Registers {@code event} for publication after the surrounding
     * transaction commits; outside a transaction (not a supported production
     * call path — every publishing verb is transactional) publishes
     * immediately.
     */
    public static void publish(ApplicationEventPublisher publisher, Object event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publisher.publishEvent(event);
                }
            });
        } else {
            publisher.publishEvent(event);
        }
    }
}
