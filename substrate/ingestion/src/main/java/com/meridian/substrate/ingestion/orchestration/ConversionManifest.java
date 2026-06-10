package com.meridian.substrate.ingestion.orchestration;

import java.util.List;
import java.util.UUID;

/**
 * Provenance manifest written by the conversion stage as {@code _manifest.json}
 * in the output directory (and copied through staging). It maps each generated
 * output file back to its ORIGINAL source document's registry row, so the
 * commit stage can:
 * <ul>
 *   <li>link Evidence rows to the real source document (not just the staged JSON);</li>
 *   <li>stamp {@code DocumentRegistry.processedByConverterRunId} on the source
 *       rows when the commit lands (sha256 dedup then skips those sources on
 *       re-convert).</li>
 * </ul>
 */
public record ConversionManifest(List<Entry> entries) {

    public static final String FILE_NAME = "_manifest.json";

    /**
     * @param outputFile         generated entity JSON file name (no directory)
     * @param sourceFile         original source file name
     * @param sourceSha256       sha256 of the original source file
     * @param documentRegistryId registry row id of the original source file
     * @param tag                document tag the source was routed under
     * @param promptTemplate     template used for LLM extraction (null for the
     *                           deterministic proposals path)
     * @param entityKind         output entity kind (e.g. "systems", "processes")
     * @param entityCount        number of records in the output file
     */
    public record Entry(
        String outputFile,
        String sourceFile,
        String sourceSha256,
        UUID documentRegistryId,
        String tag,
        String promptTemplate,
        String entityKind,
        int entityCount
    ) {}
}
