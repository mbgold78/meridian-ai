package com.meridian.substrate.ingestion.readers;

/**
 * Classification tag for an input document. Drives BOTH reader selection and
 * prompt-template selection (replacing homepoc's two parallel mechanisms:
 * the virtual-extension reader map and the {@code PROMPT_MAPPINGS} table —
 * port-map §2.4 routing fix).
 */
public enum DocumentTag {

    /** CSV inventory of IT systems. */
    SYSTEM_CSV,

    /** CSV of manual processes per spec §11.1 (structured proposals path). */
    MANUAL_PROCESS_CSV,

    /** CSV of canonical data entities. */
    ENTITY_CSV,

    /** Text/markdown manual-workflow description per spec §11.2. */
    WORKFLOW_TEXT,

    /** CSV with no recognized name pattern. */
    GENERIC_CSV,

    /** JSON with no recognized name pattern. */
    GENERIC_JSON,

    /** Text/markdown with no recognized name pattern. */
    GENERIC_TEXT,

    /** No reader available for this file type. */
    UNSUPPORTED
}
