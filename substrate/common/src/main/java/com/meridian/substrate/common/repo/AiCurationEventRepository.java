package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.AiCurationEvent;
import com.meridian.substrate.common.domain.enums.CurationSubjectKind;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Insert-only repository surface for the APPEND-ONLY {@link AiCurationEvent}
 * curation audit (engine-spec diagnostic.md §3.1/§3.2; ADR-004 D4).
 *
 * <p>Deliberately extends {@link Repository} — NOT {@code JpaRepository} — so
 * no update/delete methods exist anywhere in the type system (precedent:
 * {@code CascadeLevelActionRepository}; pinned by the ArchUnit append-only
 * repo rule, diagnostic test plan #15). {@code save} is used for initial
 * inserts only; services never mutate loaded rows, and the Postgres V7_1
 * trigger raises on any UPDATE/DELETE as the DB-level backstop (H2 dev is
 * service-enforced only — H2-green is never acceptance).
 *
 * <p>EXTENSION POINT(diagnostic-3): a batch-accept curation affordance is
 * structurally absent — per-item events only, and no bulk verb may exist
 * anywhere on this surface (P6/doc-07; ADR-004 follow-up #2 is Mike's
 * product call). Flag, don't invent.
 */
@org.springframework.stereotype.Repository
public interface AiCurationEventRepository extends Repository<AiCurationEvent, UUID> {

    /** Insert-only in practice (append-only audit) — never call with a loaded, mutated row. */
    AiCurationEvent save(AiCurationEvent event);

    Optional<AiCurationEvent> findById(UUID id);

    /** The decision history of one subject, in order — the provenance walk's curationEvents[]. */
    List<AiCurationEvent> findBySubjectKindAndSubjectIdOrderByCreatedAtAsc(
            CurationSubjectKind subjectKind, UUID subjectId);

    /** Idempotency-replay lookup (uq_curation_idem backstop — spec §9). */
    Optional<AiCurationEvent> findBySubjectKindAndSubjectIdAndIdempotencyKey(
            CurationSubjectKind subjectKind, UUID subjectId, String idempotencyKey);

    List<AiCurationEvent> findByDomainIdOrderByCreatedAtAsc(UUID domainId);
}
