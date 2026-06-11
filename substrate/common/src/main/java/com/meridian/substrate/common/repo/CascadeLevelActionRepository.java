package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.CascadeLevelAction;
import org.springframework.data.repository.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Insert-only repository surface for the APPEND-ONLY
 * {@link CascadeLevelAction} audit log (engine-spec cascade.md §4; ADR D9;
 * invariant I8).
 *
 * <p>Deliberately extends {@link Repository} — NOT {@code JpaRepository} — so
 * no update/delete methods exist anywhere in the type system: no
 * {@code delete}, {@code deleteAll}, {@code deleteById} is inherited or
 * declared. {@code save} is used for initial inserts only; services never
 * mutate loaded action rows, and the Postgres V4_1 trigger raises on any
 * UPDATE/DELETE as the DB-level backstop (H2 dev is service-enforced only,
 * precedent V2_1).
 */
@org.springframework.stereotype.Repository
public interface CascadeLevelActionRepository extends Repository<CascadeLevelAction, UUID> {

    /** Insert-only in practice (append-only log) — never call with a loaded, mutated row. */
    CascadeLevelAction save(CascadeLevelAction a);

    Optional<CascadeLevelAction> findById(UUID id);

    List<CascadeLevelAction> findByCascadeIdOrderByChainSeqAsc(UUID cascadeId);

    /** Idempotency-replay lookup under the per-cascade lock (ADR D8). */
    Optional<CascadeLevelAction> findByCascadeIdAndIdempotencyKey(UUID cascadeId, String key);

    long countByCascadeIdAndActedAtAfter(UUID cascadeId, Instant after);

    /**
     * Latest action of a cascade — the read model's {@code lastActivityAt}
     * source (spec §6.1 CascadeSummary). Additive read-only finder; the
     * insert-only discipline of this surface is untouched.
     */
    Optional<CascadeLevelAction> findFirstByCascadeIdOrderByChainSeqDesc(UUID cascadeId);
}
