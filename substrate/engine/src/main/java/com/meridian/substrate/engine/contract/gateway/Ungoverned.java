package com.meridian.substrate.engine.contract.gateway;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Auditable exemption from the {@code @Governed} PEP requirement
 * (engine-spec contracts.md §4.2; ADR-002 D2: "escapes require an explicit
 * {@code @Ungoverned(reason=…)} annotation so exemptions are auditable").
 * The §14.6 ArchUnit governed-coverage rule accepts a public mutating
 * {@code @Transactional} method only when it carries {@link Governed} or
 * this annotation — an exemption is therefore always a reviewed, reasoned,
 * grep-able decision, never silent drift.
 *
 * <p>Legitimate exemption families (each instance states its own reason):
 * engine-internal machinery that is not one of the three ADR D2 PEPs
 * (sweep steps, outbox inserts, the PDP's own decision-row writer),
 * read-surface methods that are transactional only for on-read refresh, and
 * single-evaluation deduplication where the only caller is itself governed.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Ungoverned {

    /** Why this mutating method is not a PEP — auditable, mandatory. */
    String reason();
}
