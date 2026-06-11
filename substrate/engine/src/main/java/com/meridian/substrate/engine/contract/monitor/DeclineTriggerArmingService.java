package com.meridian.substrate.engine.contract.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractDeclineTrigger;
import com.meridian.substrate.common.domain.enums.ContractStatus;
import com.meridian.substrate.common.repo.ContractDeclineTriggerRepository;
import com.meridian.substrate.common.repo.ContractMetricSampleRepository;
import com.meridian.substrate.common.repo.ContractRepository;
import com.meridian.substrate.engine.contract.ContractProperties;
import com.meridian.substrate.engine.contract.events.AfterCommit;
import com.meridian.substrate.engine.contract.events.DeclineTriggerArmedEvent;
import com.meridian.substrate.engine.contract.gateway.LatestClosedMetricValue;
import com.meridian.substrate.engine.contract.gateway.TinyDslEvaluator;
import com.meridian.substrate.engine.contract.registry.ContractActor;
import com.meridian.substrate.engine.contract.registry.ContractEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.ZoneId;
import java.util.UUID;

/**
 * §2.6 decline-trigger standing (DORMANT ⇄ ARMED), the sweep's arming pass
 * (§5.2 step 3): for ACTIVE contracts' predicate-bearing triggers, evaluate
 * the §4.4 tiny DSL and set the denormalized {@code armed}/{@code last_armed_at},
 * appending {@code DECLINE_TRIGGER_ARMED|DISARMED} events and publishing
 * {@link DeclineTriggerArmedEvent} (the {@code DECLINE_ELIGIBLE} signal —
 * subtle-destructive accent, <b>NEVER a breach</b>).
 *
 * <p>Predicate-null triggers are pure human-judgment grounds, always
 * invocable, never armed (the fixture's "sustained over two quarters" trigger
 * ships predicate-null — ADR D9's canonical revisit example).
 *
 * <p>Evaluation document: the arming pass has no action payload, so field
 * leaves resolve INDETERMINATE against an empty document by §4.4 — only
 * metric leaves (latest closed-period value via
 * {@link LatestClosedMetricValue}, shared with the gateway) can arm in v1.
 * INDETERMINATE never changes standing and is logged, never silent (§4.4).
 *
 * <p>Each contract arms in its own transaction (cascade-sweeper precedent) so
 * one bad predicate cannot poison the pass.
 */
@Service
public class DeclineTriggerArmingService {

    private static final Logger log = LoggerFactory.getLogger(DeclineTriggerArmingService.class);

    private final ContractRepository contractRepository;
    private final ContractDeclineTriggerRepository triggerRepository;
    private final ContractMetricSampleRepository metricSampleRepository;
    private final ContractEventService eventService;
    private final ContractProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public DeclineTriggerArmingService(ContractRepository contractRepository,
                                       ContractDeclineTriggerRepository triggerRepository,
                                       ContractMetricSampleRepository metricSampleRepository,
                                       ContractEventService eventService,
                                       ContractProperties properties,
                                       ApplicationEventPublisher eventPublisher,
                                       PlatformTransactionManager transactionManager,
                                       Clock clock,
                                       ObjectMapper objectMapper) {
        this.contractRepository = contractRepository;
        this.triggerRepository = triggerRepository;
        this.metricSampleRepository = metricSampleRepository;
        this.eventService = eventService;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /** The §5.2 step-3 pass over all ACTIVE contracts. */
    public void armingPass() {
        for (Contract contract : contractRepository.findByStatus(ContractStatus.ACTIVE)) {
            UUID contractId = contract.getId();
            try {
                transactionTemplate.executeWithoutResult(status -> armContract(contractId));
            } catch (RuntimeException ex) {
                log.error("Decline-trigger arming failed [contractId={}] — pass continues", contractId, ex);
            }
        }
    }

    /** One contract's triggers, one transaction. */
    void armContract(UUID contractId) {
        Contract contract = contractRepository.findById(contractId).orElse(null);
        if (contract == null || contract.getStatus() != ContractStatus.ACTIVE) {
            return;                                  // resolved since the candidate list was taken
        }
        ObjectNode emptyDocument = objectMapper.createObjectNode();
        TinyDslEvaluator.MetricResolver metrics = metricKey -> LatestClosedMetricValue.resolve(
                metricSampleRepository.findByContractIdAndMetricKey(contractId, metricKey),
                clock.instant(), zone());

        for (ContractDeclineTrigger trigger
                : triggerRepository.findByContractIdOrderBySequenceOrderAsc(contractId)) {
            if (trigger.getPredicate() == null || trigger.getPredicate().isNull()) {
                continue;                            // human judgment — always invocable, never armed
            }
            TinyDslEvaluator.Result result =
                    TinyDslEvaluator.evaluate(trigger.getPredicate(), emptyDocument, metrics);
            switch (result) {
                case MATCH -> {
                    if (!trigger.isArmed()) {
                        trigger.setArmed(true);
                        trigger.setLastArmedAt(clock.instant());
                        triggerRepository.save(trigger);
                        ObjectNode payload = objectMapper.createObjectNode();
                        payload.put("triggerText", trigger.getText());
                        eventService.append(contractId, ContractEventService.DECLINE_TRIGGER_ARMED,
                                trigger.getId(), ContractActor.system(BreachService.SWEEP_ACTOR_ID),
                                payload);
                        AfterCommit.publish(eventPublisher, new DeclineTriggerArmedEvent(
                                contractId, contract.getDomainId(), contract.getContractKey(),
                                trigger.getId(), trigger.getText()));
                        log.info("Decline trigger ARMED [contractKey={}, triggerId={}] — DECLINE_ELIGIBLE,"
                                + " never a breach (§2.6)", contract.getContractKey(), trigger.getId());
                    }
                }
                case NO_MATCH -> {
                    if (trigger.isArmed()) {
                        trigger.setArmed(false);
                        triggerRepository.save(trigger);
                        ObjectNode payload = objectMapper.createObjectNode();
                        payload.put("triggerText", trigger.getText());
                        eventService.append(contractId, ContractEventService.DECLINE_TRIGGER_DISARMED,
                                trigger.getId(), ContractActor.system(BreachService.SWEEP_ACTOR_ID),
                                payload);
                    }
                }
                case INDETERMINATE ->
                    // §4.4 never-silent: standing unchanged, the skip is recorded.
                    log.warn("Decline-trigger predicate INDETERMINATE [contractKey={}, triggerId={}] —"
                                    + " standing unchanged (unknown node or unresolvable field/metric, §4.4)",
                            contract.getContractKey(), trigger.getId());
            }
        }
    }

    private ZoneId zone() {
        return ZoneId.of(properties.getClock().getZone());
    }
}
