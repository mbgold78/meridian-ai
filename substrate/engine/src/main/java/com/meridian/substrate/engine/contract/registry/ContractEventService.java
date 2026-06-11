package com.meridian.substrate.engine.contract.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.ContractEvent;
import com.meridian.substrate.common.repo.ContractEventRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;

/**
 * Append-only {@code contract_events} writer (engine-spec contracts.md §9) —
 * the contract analog of CASCADE-DURABILITY. No update/delete path exists
 * anywhere (insert-only repository surface, §10.9; ArchUnit pin lands 3c).
 *
 * <p>Event vocabulary is deliberately restricted to the spec-NAMED types
 * (§2.2/§2.4/§2.6/§5.5/§8.3) — 3a's three lifecycle events plus 3b's
 * monitor/breach/attestation/arming vocabulary below. Proposal and
 * party-response acts are durable in their own immutable rows
 * ({@code contracts}, {@code contract_party_responses}); adding event types
 * the spec does not name would be invented vocabulary (flag-not-invent).
 *
 * <p>Hash-chain mirroring is NOT done here — it flows through the
 * {@link com.meridian.substrate.engine.contract.ContractAccountabilityMirror}
 * seam (ADR D14: bounded to activation/decline/waiver, delegated to
 * {@code DecisionLogService} under its per-domain append lock; contract
 * events never write the chain directly, §10.7).
 */
@Service
public class ContractEventService {

    /** Spec §2.2 — PROPOSED → ACTIVE (final required acceptance). */
    public static final String CONTRACT_ACTIVATED = "CONTRACT_ACTIVATED";
    /** Spec §2.2 — either decline transition; a LEGITIMATE terminal outcome. */
    public static final String CONTRACT_DECLINED = "CONTRACT_DECLINED";
    /** Spec §2.2 — ACTIVE → DECLINED citing a pre-agreed trigger. */
    public static final String DECLINE_TRIGGER_INVOKED = "DECLINE_TRIGGER_INVOKED";

    // ----- 3b vocabulary (spec §2.4/§2.6/§5.4/§5.5) -----

    /** Spec §5.4/§2.4 — a new breach row opened (monitor run or MISSED attestation). */
    public static final String BREACH_DETECTED = "BREACH_DETECTED";
    /** Spec §5.4 (ADR D13) — re-detection refreshed {@code last_confirmed_at}; never a second row. */
    public static final String BREACH_RECONFIRMED = "BREACH_RECONFIRMED";
    /** Spec §5.4/§2.4 — DETECTED → SURFACED ({@code surfaced_at} set, signal published). */
    public static final String BREACH_SURFACED = "BREACH_SURFACED";
    /** Spec §2.4 — monitor PASS over an open breach; does NOT auto-close (extension point §15 #7). */
    public static final String BREACH_COMPLIANCE_RESTORED = "BREACH_COMPLIANCE_RESTORED";
    /**
     * Spec §2.4 mandates an event per breach transition with actor identity;
     * the three human-verb names below follow the established BREACH_* pattern
     * (§2.4 names the statuses, not the event strings).
     */
    public static final String BREACH_ACKNOWLEDGED = "BREACH_ACKNOWLEDGED";
    public static final String BREACH_REMEDIATED = "BREACH_REMEDIATED";
    /** Human-only (ADR D16); mirrored to the hash chain (ADR D14). */
    public static final String BREACH_WAIVED = "BREACH_WAIVED";
    /** Spec §5.5 — appended on EVERY attestation submission (upsert keeps latest row; history lives here). */
    public static final String COMMITMENT_ATTESTED = "COMMITMENT_ATTESTED";
    /** Spec §8.3 — {@code record_commitment_progress} "appends COMMITMENT_PROGRESS event only" (3c). */
    public static final String COMMITMENT_PROGRESS = "COMMITMENT_PROGRESS";
    /** Spec §5.5 — metric sample upsert. */
    public static final String METRIC_REPORTED = "METRIC_REPORTED";
    /** Spec §2.6 — decline-trigger predicate flipped true (DECLINE_ELIGIBLE signal; NEVER a breach). */
    public static final String DECLINE_TRIGGER_ARMED = "DECLINE_TRIGGER_ARMED";
    /** Spec §2.6 — predicate flipped back false. */
    public static final String DECLINE_TRIGGER_DISARMED = "DECLINE_TRIGGER_DISARMED";

    private final ContractEventRepository eventRepository;
    private final Clock clock;

    public ContractEventService(ContractEventRepository eventRepository, Clock clock) {
        this.eventRepository = eventRepository;
        this.clock = clock;
    }

    /**
     * Appends one timeline event ({@code occurred_at} from the injectable
     * engine clock — S15 demo determinism). Same transaction as the state
     * change it records (§2.2 side-effects column).
     */
    public ContractEvent append(UUID contractId, String eventType, UUID refId,
                                ContractActor actor, JsonNode payload) {
        ContractEvent event = new ContractEvent();
        event.setContractId(contractId);
        event.setEventType(eventType);
        event.setRefId(refId);
        event.setActorType(actor.type());
        event.setActorId(actor.id());
        event.setPayload(payload);
        event.setOccurredAt(clock.instant());
        return eventRepository.save(event);
    }
}
