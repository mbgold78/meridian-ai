package com.meridian.substrate.common.governance;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Module-level marker for a whole module declared EXEMPT from the contract
 * enforcement gateway (engine-spec contracts.md §4.2/§14.6; ADR-002 D3).
 * Exactly one v1 use exists: {@code IngestionApplication} — the ingestion
 * bulk-commit path writes canonical entities without transiting app/engine
 * services and is declared (not discovered) exempt, with the reason recorded
 * here so the exemption is auditable. The §14.6 ArchUnit suite pins both the
 * annotation's presence and the ADR D3 reason string.
 *
 * <p><b>Placement deviation from spec §9</b> (which lists the governance
 * annotations under {@code engine.contract.gateway}): this ONE annotation
 * lives in {@code common} because the ingestion module depends only on
 * {@code common} (ADR D3 module rule — "ingestion never sees engine", pinned
 * by the same ArchUnit suite). Putting it in engine would force the exempted
 * module to import the enforcement engine it is exempt from. The method-level
 * {@code @Governed} / {@code @Ungoverned} siblings stay in
 * {@code engine.contract.gateway} per §9.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface UngovernedModule {

    /** The auditable exemption rationale — the ADR D3 reason string. */
    String reason();
}
