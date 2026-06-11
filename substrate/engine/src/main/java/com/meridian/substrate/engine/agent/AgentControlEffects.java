package com.meridian.substrate.engine.agent;

import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.enums.AgentControlConfirmationState;
import com.meridian.substrate.common.domain.enums.AgentControlEffect;
import com.meridian.substrate.common.domain.enums.AgentExecutionLocus;
import com.meridian.substrate.engine.agent.read.AgentControlTracking;
import com.meridian.substrate.engine.agent.read.AgentEffectDisclosure;
import com.meridian.substrate.engine.agent.read.AgentEnforcement;
import com.meridian.substrate.engine.contract.gateway.AgentRegistryGate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The §5.3 control-effect derivation (engine-spec agents.md §5.3/§6; ADR-003
 * D6/D17): every Ops Center verb declares — BEFORE and AFTER the click —
 * exactly which of the two honest effects it has at this agent's
 * {@code execution_locus}: {@code GATEWAY_BLOCK} (the registry flip IS the
 * control plane; the gateway denies the next governed call) or
 * {@code RECORDED_INTENT} (substrate cannot — or, in shadow/off posture,
 * does not — stop the runtime; intent is recorded, the chain notified,
 * acknowledgment/confirmation tracked). Substrate never claims to have
 * stopped what it does not stop (spec non-negotiable #1 / invariant I8).
 *
 * <p><b>The gateway posture fact (ADR-003 D7/D17, 6c):</b>
 * {@link AgentRegistryGate#posture()} composes the BEAN-PRESENCE fact
 * ({@code enforcesRegistry()}) with the single shared enforcement ladder
 * {@code substrate.contracts.enforcement.mode}. GATEWAY_BLOCK is claimed for
 * {@link AgentRegistryGate.Posture#BLOCKING} ONLY: while the contracts-owned
 * no-op gate is primary (ABSENT — module missing), EVERY verb derives
 * {@code RECORDED_INTENT} with {@value #DISCLOSURE_PENDING_GATEWAY}; while
 * the ladder is {@code shadow} (OBSERVE) the gateway rows WOULD_DENY and
 * proceeds, and while it is {@code off} (INERT) the gateway evaluates
 * nothing — claiming "the next governed call is denied" in either would be
 * fabricated control. Honesty is not configuration in the degraded
 * dimension (the bean fact, never a property), and never over-claims in the
 * mode dimension (the ladder is engagement config and the claim follows it).
 *
 * <p>EXTENSION POINT(agent-13): per-agent enforcement-mode override / a
 * second enforcement ladder — single shared ladder only
 * ({@code substrate.contracts.enforcement.mode}, ADR-003 D7); revisit on the
 * first engagement needing independent rollout cadence.
 */
public final class AgentControlEffects {

    /**
     * The degraded-posture disclosure (ADR-003 D17) — asserted verbatim by
     * the 6b-window acceptance and by the 6c degraded-mode suite (test plan
     * #16: contracts absent ⇒ all verbs RECORDED_INTENT + this disclosure).
     */
    public static final String DISCLOSURE_PENDING_GATEWAY = "enforcement pending gateway";

    private AgentControlEffects() {
    }

    // ------------------------------------------------------------ per-verb effect

    /**
     * §5.3 row 1+2 — the suspension/throttle verbs. With the gateway
     * BLOCKING: SUBSTRATE_MCP ⇒ GATEWAY_BLOCK; ORCHESTRATOR ⇒
     * RECORDED_INTENT + preflight/quarantine disclosure (in-flight dispatches
     * CANNOT be aborted — the five-tool seam has no cancel; EXTENSION
     * POINT(agent-7): a cancel/pause consumption tool is a flagged Mike seam
     * revision, never wired unilaterally); CUSTOMER_SYSTEM ⇒ RECORDED_INTENT
     * + ack/confirm tracking. OBSERVE/INERT: SUBSTRATE_MCP is
     * RECORDED_INTENT with the honest shared-ladder disclosure (shadow rows
     * WOULD_DENY + signals zombies, never blocks; off evaluates nothing).
     * ABSENT: RECORDED_INTENT everywhere with the pending-gateway disclosure.
     */
    public static AgentEffectDisclosure suspensionEffect(AgentExecutionLocus locus,
                                                         AgentRegistryGate.Posture posture) {
        if (posture == AgentRegistryGate.Posture.ABSENT) {
            return new AgentEffectDisclosure(AgentControlEffect.RECORDED_INTENT,
                    DISCLOSURE_PENDING_GATEWAY);
        }
        return switch (locus) {
            case SUBSTRATE_MCP -> switch (posture) {
                case BLOCKING -> new AgentEffectDisclosure(AgentControlEffect.GATEWAY_BLOCK,
                        "registry flip enforced at the gateway: the agent's next governed call is"
                                + " denied (guard cache invalidated, 30s TTL net)");
                case OBSERVE -> new AgentEffectDisclosure(AgentControlEffect.RECORDED_INTENT,
                        "registry flip recorded; the shared enforcement ladder"
                                + " (substrate.contracts.enforcement.mode) is in SHADOW: this"
                                + " agent's governed calls are rowed would-deny and zombie"
                                + " activity is signaled, not blocked — enforce mode makes this"
                                + " a hard gateway block");
                case INERT -> new AgentEffectDisclosure(AgentControlEffect.RECORDED_INTENT,
                        "registry flip recorded; the shared enforcement ladder"
                                + " (substrate.contracts.enforcement.mode) is OFF: governed"
                                + " calls are not evaluated at the gateway — intent recorded"
                                + " and the oversight chain notified");
                case ABSENT -> throw new IllegalStateException("handled above");
            };
            case ORCHESTRATOR -> new AgentEffectDisclosure(AgentControlEffect.RECORDED_INTENT,
                    "records intent; no new dispatches are submitted on this mandate and"
                            + " dispatch results are quarantined at apply — in-flight dispatches"
                            + " cannot be aborted");
            case CUSTOMER_SYSTEM -> new AgentEffectDisclosure(AgentControlEffect.RECORDED_INTENT,
                    "records intent and notifies the oversight chain; substrate cannot reach"
                            + " this runtime — runtime acknowledgment and human confirmation are"
                            + " tracked, and sighting absence never auto-confirms");
        };
    }

    /**
     * §6.1 "disclosure before the click": the detail DTO's
     * {@code controlEffects} map for the three disclosed verbs.
     */
    public static Map<String, AgentEffectDisclosure> controlEffectsOf(AgentRegistration reg,
                                                                      AgentRegistryGate.Posture posture) {
        AgentEffectDisclosure effect = suspensionEffect(reg.getExecutionLocus(), posture);
        Map<String, AgentEffectDisclosure> effects = new LinkedHashMap<>();
        effects.put("pause", effect);
        effects.put("kill", effect);
        effects.put("throttle", effect);
        return effects;
    }

    // ------------------------------------------------- per-response enforcement

    /**
     * The verb-response enforcement block for a SUSPENSION verb
     * (pause/kill/retire — the verbs that open RECORDED_INTENT
     * confirmation tracking): effect per locus/gateway-posture fact +
     * {@code confirmationState} from the registration's control block.
     */
    public static AgentEnforcement suspensionEnforcement(AgentRegistration reg,
                                                         AgentRegistryGate.Posture posture) {
        AgentEffectDisclosure effect = suspensionEffect(reg.getExecutionLocus(), posture);
        AgentControlConfirmationState state =
                effect.effect() == AgentControlEffect.GATEWAY_BLOCK
                        ? AgentControlConfirmationState.NOT_REQUIRED
                        : confirmationStateOf(reg);
        return new AgentEnforcement(effect.effect(), state, effect.disclosure());
    }

    /**
     * The verb-response enforcement block for every NON-suspension verb
     * (resume/promote/throttle/oversight/attest/reviews/interventions/
     * principal/confirm): §5.3 row 3 — substrate-held state is the control
     * plane for resume/promote IN EVERY ladder mode (nothing needs blocking;
     * the registry column flip is the whole act); elsewhere recorded +
     * notified. Confirmation is {@code NOT_REQUIRED} (only RECORDED_INTENT
     * suspensions track pending→acknowledged→confirmed).
     */
    public static AgentEnforcement statefulEnforcement(AgentRegistration reg,
                                                       AgentRegistryGate.Posture posture) {
        AgentEffectDisclosure effect = suspensionEffect(reg.getExecutionLocus(), posture);
        return new AgentEnforcement(AgentControlEffect.RECORDED_INTENT,
                AgentControlConfirmationState.NOT_REQUIRED,
                posture != AgentRegistryGate.Posture.ABSENT
                        ? "substrate-held registry state updated and the oversight chain notified"
                        : effect.disclosure());
    }

    /**
     * §5.3 {@code confirmationState} ladder: NOT_REQUIRED when nothing is
     * pending; PENDING until runtime ack (M17) and/or human confirmation
     * (M16). CONFIRMED does not require ACKNOWLEDGED (a human may verify a
     * runtime that never acked); neither is EVER auto-derived from sighting
     * absence.
     */
    public static AgentControlConfirmationState confirmationStateOf(AgentRegistration reg) {
        if (reg.getControlPendingVerb() == null) {
            return AgentControlConfirmationState.NOT_REQUIRED;
        }
        if (reg.getControlConfirmedAt() != null) {
            return AgentControlConfirmationState.CONFIRMED;
        }
        if (reg.getControlAcknowledgedAt() != null) {
            return AgentControlConfirmationState.ACKNOWLEDGED;
        }
        return AgentControlConfirmationState.PENDING;
    }

    /** The detail DTO's {@code enforcement} block (spec §7.3). */
    public static AgentControlTracking controlTrackingOf(AgentRegistration reg) {
        return new AgentControlTracking(
                reg.getControlPendingVerb(),
                confirmationStateOf(reg),
                reg.getControlRequestedAt(),
                reg.getControlAcknowledgedAt(),
                reg.getControlConfirmedAt(),
                reg.getControlConfirmedByLabel());
    }
}
