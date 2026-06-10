package com.meridian.substrate.ingestion.readers;

/**
 * Result of routing a document: its classification tag plus the reader that
 * will extract it. {@code reader} is null iff {@code tag == UNSUPPORTED}.
 */
public record RoutedDocument(
    DocumentTag tag,
    FileReader reader
) {
    public boolean isSupported() {
        return tag != DocumentTag.UNSUPPORTED && reader != null;
    }
}
