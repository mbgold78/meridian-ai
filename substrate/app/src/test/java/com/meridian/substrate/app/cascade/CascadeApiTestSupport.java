package com.meridian.substrate.app.cascade;

import com.meridian.substrate.common.domain.Cascade;
import com.meridian.substrate.common.domain.CascadeDraft;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.domain.enums.CascadeAuthoringMode;
import com.meridian.substrate.common.domain.enums.CascadeDraftStatus;
import com.meridian.substrate.common.repo.CascadeDraftRepository;
import com.meridian.substrate.common.repo.CascadeLevelActionRepository;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.engine.cascade.CascadeActor;
import com.meridian.substrate.engine.cascade.CascadeLifecycleService;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

/**
 * Seeding helpers for the cascade read-API tests: every cascade is created by
 * driving the REAL {@link CascadeLifecycleService} verb path (draft → issue →
 * cascade → …) — never by row inserts (the ADR D16 discipline). Runs on the
 * dev H2-PG-mode profile; Testcontainers-Postgres in CI is the acceptance
 * gate, H2-green never is.
 */
public abstract class CascadeApiTestSupport {

    protected static final String ORIGINATOR = "Operating Partner";
    protected static final String L1_OWNER = "Curasync COO";
    protected static final String L2_OWNER = "Curasync HIM Director";

    @Autowired
    protected DomainRepository domainRepository;
    @Autowired
    protected CascadeDraftRepository draftRepository;
    @Autowired
    protected CascadeLevelActionRepository actionRepository;
    @Autowired
    protected CascadeLifecycleService lifecycle;

    protected UUID createDomain(String name) {
        Domain d = new Domain();
        d.setName(name + " " + UUID.randomUUID());
        d.setCreatedBy("test");
        return domainRepository.save(d).getId();
    }

    protected CascadeDraft openDraft(UUID domainId, UUID groupId, UUID affectedDomainId) {
        CascadeDraft draft = new CascadeDraft();
        draft.setDomainId(domainId);
        draft.setAuthorLabel(ORIGINATOR);
        draft.setOriginalCritique("Vendor cost overrun is eroding the synergy case");
        draft.setAcceptanceCriteria("A remediation plan, or a documented Decline");
        draft.setAuthoringMode(CascadeAuthoringMode.FREE_FORM);
        draft.setStatus(CascadeDraftStatus.OPEN);
        draft.setCascadeGroupId(groupId);
        draft.setAffectedDomainId(affectedDomainId);
        return draftRepository.save(draft);
    }

    protected Cascade issue(CascadeDraft draft, UUID originatorMemberId,
                            String l1Owner, UUID l1OwnerMemberId) {
        return lifecycle.issue(draft.getId(), new CascadeLifecycleService.IssueCommand(
                key(), new CascadeActor(originatorMemberId, ORIGINATOR), l1Owner, l1OwnerMemberId, null));
    }

    protected Cascade cascadeLevel(UUID cascadeId, int depth, String actor,
                                   String nextOwner, UUID nextOwnerMemberId) {
        return lifecycle.cascade(cascadeId, depth, new CascadeLifecycleService.CascadeCommand(
                key(), null, CascadeActor.of(actor), "Refined by " + actor, nextOwnerMemberId, nextOwner));
    }

    protected Cascade decline(UUID cascadeId, int depth, String actor) {
        return lifecycle.decline(cascadeId, depth, new CascadeLifecycleService.DeclineCommand(
                key(), null, CascadeActor.of(actor), "Documented pushback by " + actor));
    }

    protected Cascade absorb(UUID cascadeId, int depth, String actor) {
        return lifecycle.absorb(cascadeId, depth, new CascadeLifecycleService.AbsorbCommand(
                key(), null, CascadeActor.of(actor), "Absorbed by " + actor));
    }

    protected Cascade resolveLooksGood(UUID cascadeId) {
        return lifecycle.resolve(cascadeId, new CascadeLifecycleService.ResolveCommand(
                key(), null, CascadeActor.of(ORIGINATOR),
                CascadeLifecycleService.Resolution.LOOKS_GOOD, "Outcome accepted", null));
    }

    protected Cascade resolveDeclined(UUID cascadeId, UUID acceptedDeclineActionId) {
        return lifecycle.resolve(cascadeId, new CascadeLifecycleService.ResolveCommand(
                key(), null, CascadeActor.of(ORIGINATOR),
                CascadeLifecycleService.Resolution.DECLINED, "Decline accepted", acceptedDeclineActionId));
    }

    /** The c-vendor-cost shape: issue → L1 cascade → L2 decline ⇒ decline-pending. */
    protected Cascade seedDeclinePending(UUID domainId, UUID groupId) {
        Cascade c = issue(openDraft(domainId, groupId, null), null, L1_OWNER, null);
        cascadeLevel(c.getId(), 1, L1_OWNER, L2_OWNER, null);
        return decline(c.getId(), 2, L2_OWNER);
    }

    /** issue → L1 cascade ⇒ frontier L2 LOOKING, escalating. */
    protected Cascade seedEscalating(UUID domainId, UUID groupId,
                                     String l2Owner, UUID l2OwnerMemberId) {
        Cascade c = issue(openDraft(domainId, groupId, null), null, L1_OWNER, null);
        return cascadeLevel(c.getId(), 1, L1_OWNER, l2Owner, l2OwnerMemberId);
    }

    /** issue → L1 absorb ⇒ frontier L1 ABSORBED, absorbed. */
    protected Cascade seedAbsorbed(UUID domainId) {
        Cascade c = issue(openDraft(domainId, null, null), null, L1_OWNER, null);
        return absorb(c.getId(), 1, L1_OWNER);
    }

    protected UUID latestActionId(UUID cascadeId) {
        return actionRepository.findFirstByCascadeIdOrderByChainSeqDesc(cascadeId)
                .orElseThrow().getId();
    }

    protected static String key() {
        return UUID.randomUUID().toString();
    }
}
