package com.meridian.substrate.app.capability.service;

import com.meridian.substrate.app.api.v1.request.CapabilityCreateRequest;
import com.meridian.substrate.app.api.v1.request.CapabilityLinkRequest;
import com.meridian.substrate.app.api.v1.request.CapabilityUpdateRequest;
import com.meridian.substrate.app.core.exception.EntityNotFoundException;
import com.meridian.substrate.common.domain.Capability;
import com.meridian.substrate.common.domain.CapabilityNameHistory;
import com.meridian.substrate.common.domain.CapabilityProcessLink;
import com.meridian.substrate.common.domain.CapabilitySystemLink;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.domain.enums.CapabilityStatus;
import com.meridian.substrate.common.repo.CapabilityNameHistoryRepository;
import com.meridian.substrate.common.repo.CapabilityProcessLinkRepository;
import com.meridian.substrate.common.repo.CapabilityRepository;
import com.meridian.substrate.common.repo.CapabilitySystemLinkRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * CRUD for capabilities, name history, and capability↔process/system links.
 * Phase 1 scope: minimal create/read/update. Renaming displayName auto-writes
 * a {@link CapabilityNameHistory} row. No generative logic, no validation beyond
 * basic nullability.
 */
@Service
public class CapabilityService {

    private static final Logger log = LoggerFactory.getLogger(CapabilityService.class);

    private final CapabilityRepository capabilityRepository;
    private final CapabilityNameHistoryRepository nameHistoryRepository;
    private final CapabilityProcessLinkRepository processLinkRepository;
    private final CapabilitySystemLinkRepository systemLinkRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public CapabilityService(CapabilityRepository capabilityRepository,
                             CapabilityNameHistoryRepository nameHistoryRepository,
                             CapabilityProcessLinkRepository processLinkRepository,
                             CapabilitySystemLinkRepository systemLinkRepository) {
        this.capabilityRepository = capabilityRepository;
        this.nameHistoryRepository = nameHistoryRepository;
        this.processLinkRepository = processLinkRepository;
        this.systemLinkRepository = systemLinkRepository;
    }

    @Transactional(readOnly = true)
    public List<Capability> listByDomain(UUID domainId) {
        return capabilityRepository.findByDomainId(domainId);
    }

    @Transactional(readOnly = true)
    public Capability get(UUID id) {
        return capabilityRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Capability", id));
    }

    @Transactional
    public Capability create(CapabilityCreateRequest req) {
        Capability c = new Capability();
        c.setDomainId(req.domainId());
        c.setParentCapabilityId(req.parentCapabilityId());
        c.setName(req.name());
        c.setDisplayName(req.displayName());
        c.setDescription(req.description());
        c.setLevel(req.level() == null ? 0 : req.level());
        c.setStatus(req.status() == null ? CapabilityStatus.DRAFT : req.status());
        c.setAiGenerated(Boolean.TRUE.equals(req.aiGenerated()));
        c.setCreatedBy("api");

        // Append to the end of the sibling group — users expect a newly
        // created capability to appear after existing siblings, not
        // collide with them at sequence 0 (the DEFAULT the column carries).
        List<Capability> siblings = loadSiblings(req.domainId(), req.parentCapabilityId(), false);
        c.setSequenceOrder(siblings.size());

        Capability saved = capabilityRepository.save(c);
        log.debug("Created capability [id={}, name={}, sequence={}]",
                saved.getId(), saved.getName(), saved.getSequenceOrder());
        return saved;
    }

    @Transactional
    public Capability update(UUID id, CapabilityUpdateRequest req) {
        Capability c = get(id);

        if (req.displayName() != null && !Objects.equals(req.displayName(), c.getDisplayName())) {
            CapabilityNameHistory h = new CapabilityNameHistory();
            h.setCapabilityId(c.getId());
            h.setFromDisplayName(c.getDisplayName());
            h.setToDisplayName(req.displayName());
            h.setRationale(req.renameRationale());
            h.setCreatedBy("api");
            nameHistoryRepository.save(h);
            c.setDisplayName(req.displayName());
        }
        if (req.description() != null) {
            c.setDescription(req.description());
        }
        if (req.status() != null) {
            c.setStatus(req.status());
        }
        return capabilityRepository.save(c);
    }

    /**
     * Reparent a capability — moves it under a new parent (or to root when
     * {@code newParentId == null}). Split from {@link #update} to avoid
     * partial-PATCH ambiguity (a PATCH body that omits parentCapabilityId
     * must not be silently interpreted as "move to root").
     *
     * <p>Rejects the call when {@code newParentId} equals {@code id} — a
     * capability cannot be its own parent. Does NOT yet detect deeper
     * cycles (A→B→A via a multi-hop graph); the Capability Designer's UI
     * prevents the drag gesture, and server-side cycle detection is a
     * post-port hardening item (carried from homepoc Stage 2B-2 backlog).
     */
    @Transactional
    public Capability reparent(UUID id, UUID newParentId) {
        Capability c = get(id);
        if (newParentId != null && newParentId.equals(id)) {
            throw new IllegalArgumentException("Capability cannot be its own parent");
        }
        if (Objects.equals(newParentId, c.getParentCapabilityId())) {
            return c;
        }
        c.setParentCapabilityId(newParentId);
        // Land at the end of the new sibling group — same rationale as
        // create(). The caller can immediately reorder if they want a
        // different position.
        List<Capability> newSiblings = loadSiblings(c.getDomainId(), newParentId, false);
        // loadSiblings returns the PRE-reparent state; our capability is
        // not yet in the new group. Size() gives the right tail index.
        c.setSequenceOrder(newSiblings.size());
        return capabilityRepository.save(c);
    }

    /**
     * Reorder a capability within its current sibling group.
     *
     * <p><b>Concurrency — pessimistic anchor lock on the sibling-group
     * container.</b> The "anchor" is the stable row that groups the
     * siblings: the parent Capability row when
     * {@code c.parentCapabilityId != null}, or the Domain row otherwise.
     * Locking the anchor serializes all reorder operations for that
     * sibling group.
     *
     * <p>Parent-Capability anchor (not Domain for all cases) is
     * deliberate: a Domain-wide lock would force every reorder in the
     * entire domain to serialize behind every other reorder, even for
     * completely unrelated subtrees. Parent-row anchoring keeps the
     * lock scope proportional to what actually needs serialization —
     * siblings of one parent — while still using the same
     * "lock-a-stable-container-then-mutate-contents" pattern the
     * {@code BrandingConfig.activate} defense uses.
     *
     * <p>Two-level lock: outer anchor (parent/domain) + inner
     * {@code PESSIMISTIC_WRITE} on all sibling rows. The inner lock
     * protects against a concurrent single-sibling mutation racing the
     * cascade write.
     *
     * <p>Cascade strategy: rewrite the whole sibling group with
     * contiguous 0-based {@code sequenceOrder} values after placing the
     * moved capability at the clamped target position. Rewriting the
     * entire group guarantees no gaps and no duplicates regardless of
     * prior state (including any drift from a bad backfill or from a
     * racing writer that slipped through in an earlier release).
     */
    @Transactional
    public Capability reorder(UUID id, int newSequence) {
        Capability c = get(id);
        UUID parentId = c.getParentCapabilityId();
        UUID domainId = c.getDomainId();

        // 1. Outer anchor lock — parent Capability row if it exists, else
        //    Domain row (for top-level siblings).
        if (parentId != null) {
            entityManager.find(Capability.class, parentId, LockModeType.PESSIMISTIC_WRITE);
        } else {
            entityManager.find(Domain.class, domainId, LockModeType.PESSIMISTIC_WRITE);
        }

        // 2. Inner lock — PESSIMISTIC_WRITE on every sibling row. Defense-
        //    in-depth; redundant with the anchor lock for pure reorder-vs-
        //    reorder races but protects against reorder-vs-single-sibling-
        //    update from unrelated code paths.
        List<Capability> siblings = new ArrayList<>(loadSiblings(domainId, parentId, true));

        // Re-read the moved capability from the locked sibling set so we
        // don't operate on a stale snapshot.
        Capability moved = siblings.stream()
                .filter(s -> s.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Capability " + id + " vanished from its sibling group "
                                + "during reorder — concurrent reparent?"));

        // Sort by current sequenceOrder just in case the locked read came
        // back unsorted for any reason.
        siblings.sort((a, b) -> Integer.compare(a.getSequenceOrder(), b.getSequenceOrder()));

        // Pull the moved row out and reinsert at the clamped target position.
        siblings.removeIf(s -> s.getId().equals(id));
        int clamped = Math.max(0, Math.min(newSequence, siblings.size()));
        siblings.add(clamped, moved);

        // Rewrite contiguous 0-based sequence values.
        for (int i = 0; i < siblings.size(); i++) {
            Capability s = siblings.get(i);
            if (s.getSequenceOrder() != i) {
                s.setSequenceOrder(i);
                capabilityRepository.save(s);
            }
        }
        log.debug("Reordered capability [id={}, newSequence={}, groupSize={}]",
                id, clamped, siblings.size());
        return moved;
    }

    /**
     * Load a sibling group — the capabilities sharing the given
     * {@code domainId} + {@code parentCapabilityId}. When
     * {@code lockForUpdate == true}, the rows are returned with a
     * {@code PESSIMISTIC_WRITE} lock held until the enclosing transaction
     * commits.
     */
    private List<Capability> loadSiblings(UUID domainId, UUID parentId, boolean lockForUpdate) {
        if (parentId == null) {
            return lockForUpdate
                    ? capabilityRepository.findRootSiblingsForUpdate(domainId)
                    : capabilityRepository.findRootSiblings(domainId);
        }
        return lockForUpdate
                ? capabilityRepository.findSiblingsUnderParentForUpdate(domainId, parentId)
                : capabilityRepository.findSiblingsUnderParent(domainId, parentId);
    }

    @Transactional(readOnly = true)
    public List<CapabilityNameHistory> getNameHistory(UUID capabilityId) {
        return nameHistoryRepository.findByCapabilityIdOrderByCreatedAtAsc(capabilityId);
    }

    // ---- process links ------------------------------------------------------

    @Transactional(readOnly = true)
    public List<CapabilityProcessLink> getProcessLinks(UUID capabilityId) {
        return processLinkRepository.findByCapabilityId(capabilityId);
    }

    @Transactional
    public CapabilityProcessLink createProcessLink(CapabilityLinkRequest req) {
        CapabilityProcessLink link = new CapabilityProcessLink();
        link.setCapabilityId(req.capabilityId());
        link.setProcessId(req.targetId());
        link.setLinkType(req.linkType());
        link.setAiInferred(Boolean.TRUE.equals(req.aiInferred()));
        link.setCreatedBy("api");
        return processLinkRepository.save(link);
    }

    @Transactional
    public void deleteProcessLink(UUID linkId) {
        processLinkRepository.deleteById(linkId);
    }

    // ---- system links -------------------------------------------------------

    @Transactional(readOnly = true)
    public List<CapabilitySystemLink> getSystemLinks(UUID capabilityId) {
        return systemLinkRepository.findByCapabilityId(capabilityId);
    }

    @Transactional
    public CapabilitySystemLink createSystemLink(CapabilityLinkRequest req) {
        CapabilitySystemLink link = new CapabilitySystemLink();
        link.setCapabilityId(req.capabilityId());
        link.setSystemId(req.targetId());
        link.setLinkType(req.linkType());
        link.setAiInferred(Boolean.TRUE.equals(req.aiInferred()));
        link.setCreatedBy("api");
        return systemLinkRepository.save(link);
    }

    @Transactional
    public void deleteSystemLink(UUID linkId) {
        systemLinkRepository.deleteById(linkId);
    }
}
