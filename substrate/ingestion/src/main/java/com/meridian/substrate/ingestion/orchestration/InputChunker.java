package com.meridian.substrate.ingestion.orchestration;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits extracted text that exceeds the per-request character budget into
 * chunks on natural boundaries, so large inputs no longer silently overflow
 * the model context (port-map §2.4 "add chunking for context overflow").
 *
 * <p>Boundary preference: CSV row markers ({@code --- Row N ---}, produced by
 * the CSV readers) → blank-line paragraphs → hard slices as a last resort.
 * Extracted record arrays from each chunk are merged by the pipeline.
 */
@Component
public class InputChunker {

    private static final String ROW_MARKER = "--- Row ";

    /**
     * Split {@code text} into chunks of at most {@code maxChars} characters.
     * Returns a single-element list when the input already fits.
     */
    public List<String> chunk(String text, int maxChars) {
        if (text == null || text.isEmpty()) {
            return List.of("");
        }
        if (maxChars <= 0 || text.length() <= maxChars) {
            return List.of(text);
        }

        List<String> segments = text.contains(ROW_MARKER)
                ? splitOnRowMarkers(text)
                : splitOnParagraphs(text);

        return pack(segments, maxChars);
    }

    /** Splits on CSV row markers, keeping each marker with its row body. */
    private List<String> splitOnRowMarkers(String text) {
        List<String> segments = new ArrayList<>();
        int idx = text.indexOf(ROW_MARKER);
        if (idx > 0) {
            segments.add(text.substring(0, idx));
        }
        while (idx >= 0) {
            int next = text.indexOf(ROW_MARKER, idx + ROW_MARKER.length());
            segments.add(next >= 0 ? text.substring(idx, next) : text.substring(idx));
            idx = next;
        }
        return segments;
    }

    private List<String> splitOnParagraphs(String text) {
        List<String> segments = new ArrayList<>();
        for (String para : text.split("\n\n")) {
            if (!para.isEmpty()) {
                segments.add(para + "\n\n");
            }
        }
        if (segments.isEmpty()) {
            segments.add(text);
        }
        return segments;
    }

    /** Accumulates segments into buffers of at most maxChars, hard-splitting oversized segments. */
    private List<String> pack(List<String> segments, int maxChars) {
        List<String> chunks = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();

        for (String segment : segments) {
            if (segment.length() > maxChars) {
                if (buffer.length() > 0) {
                    chunks.add(buffer.toString());
                    buffer.setLength(0);
                }
                for (int i = 0; i < segment.length(); i += maxChars) {
                    chunks.add(segment.substring(i, Math.min(segment.length(), i + maxChars)));
                }
                continue;
            }
            if (buffer.length() + segment.length() > maxChars && buffer.length() > 0) {
                chunks.add(buffer.toString());
                buffer.setLength(0);
            }
            buffer.append(segment);
        }
        if (buffer.length() > 0) {
            chunks.add(buffer.toString());
        }
        return chunks;
    }
}
