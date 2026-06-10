package com.meridian.substrate.ingestion.readers;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Reads a source file and produces raw extracted content.
 *
 * <p>{@link #readerId()} is a stable identifier for this reader (historically
 * the supported file extension). Routing is performed explicitly by
 * {@link ReaderRouter} on document tags — homepoc's "virtual extension" hack
 * (readers registered under fake extensions like {@code manual-process-csv})
 * is gone; the ids remain as routing keys.
 */
public interface FileReader {

    /**
     * Stable identifier for this reader. For plain readers this is the real
     * file extension ("csv", "json", "txt"); for specialized readers it is a
     * routing key ("manual-process-csv", "workflow").
     */
    String readerId();

    /**
     * Read the given file and return its raw content.
     */
    RawContent read(Path file) throws IOException;
}
