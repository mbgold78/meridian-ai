package com.meridian.substrate.ingestion.source;

import java.util.List;

/**
 * Abstraction over document sources. V1 uses local files;
 * a future implementation adds an indexed/RAG-backed source
 * (sourceType "tachyon-ingested" is reserved — port-map §2.4).
 */
public interface DocumentSource {

    /**
     * Fetch the full document content for the given reference.
     */
    RawDocument fetch(DocumentRef ref);

    /**
     * List available documents matching the query criteria.
     */
    List<DocumentRef> list(SourceQuery query);
}
