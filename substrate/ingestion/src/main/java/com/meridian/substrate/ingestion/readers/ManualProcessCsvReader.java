package com.meridian.substrate.ingestion.readers;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reads CSV files specifically formatted for manual process intake (spec Section 11.1).
 * <p>
 * Expected columns (order-agnostic, header-driven):
 * name, description, classification, frequency, primary_owner, business_line,
 * defense_line, documentation_quality, estimated_fte, risk_rating,
 * linked_systems, participants, notes.
 * <p>
 * {@code linked_systems} is pipe-delimited: {@code Archer:COMPENSATES_FOR|IssuesPortal:BRIDGES_FROM}
 * <p>
 * {@code participants} is pipe-delimited: {@code J Smith:OWNER|M Lee:EXECUTOR}
 * <p>
 * Returns both raw text extraction AND structured {@link ManualProcessProposal} records
 * in the metadata under the key {@link #PROPOSALS_METADATA_KEY}. The conversion
 * pipeline consumes the proposals directly (deterministic, no LLM round-trip) —
 * homepoc parsed them and then discarded them (port-map §2.4 routing fix).
 */
@Component
public class ManualProcessCsvReader implements FileReader {

    private static final Logger log = LoggerFactory.getLogger(ManualProcessCsvReader.class);

    /** Metadata key under which structured proposals are returned. */
    public static final String PROPOSALS_METADATA_KEY = "proposals";

    /**
     * Routing key (homepoc's "virtual extension"). Files are routed here
     * explicitly by {@link ReaderRouter} on tag {@link DocumentTag#MANUAL_PROCESS_CSV}.
     */
    @Override
    public String readerId() {
        return "manual-process-csv";
    }

    @Override
    public RawContent read(Path file) throws IOException {
        long size = Files.size(file);

        List<String[]> allRows;
        try (CSVReader reader = new CSVReader(Files.newBufferedReader(file))) {
            allRows = reader.readAll();
        } catch (CsvException e) {
            throw new IOException("Failed to parse manual-process CSV: " + file, e);
        }

        if (allRows.isEmpty()) {
            return new RawContent(
                file.getFileName().toString(),
                "text/csv",
                "",
                size,
                Map.of("rowCount", 0, PROPOSALS_METADATA_KEY, List.of())
            );
        }

        // Build header index (case-insensitive, trimmed)
        String[] rawHeaders = allRows.get(0);
        Map<String, Integer> headerIndex = new HashMap<>();
        for (int i = 0; i < rawHeaders.length; i++) {
            headerIndex.put(rawHeaders[i].trim().toLowerCase(), i);
        }

        List<String[]> dataRows = allRows.subList(1, allRows.size());

        // Build raw text and structured proposals in one pass
        StringBuilder sb = new StringBuilder();
        List<ManualProcessProposal> proposals = new ArrayList<>();

        for (int rowIdx = 0; rowIdx < dataRows.size(); rowIdx++) {
            String[] row = dataRows.get(rowIdx);

            // Raw text representation
            sb.append("--- Row ").append(rowIdx + 1).append(" ---\n");
            for (int col = 0; col < rawHeaders.length; col++) {
                String value = col < row.length ? row[col].trim() : "";
                sb.append(rawHeaders[col].trim()).append(": ").append(value).append("\n");
            }
            sb.append("\n");

            // Structured proposal
            try {
                ManualProcessProposal proposal = parseProposal(row, headerIndex);
                proposals.add(proposal);
            } catch (Exception e) {
                log.warn("Failed to parse manual process proposal at row {}: {}", rowIdx + 1, e.getMessage());
            }
        }

        List<String> columnNames = Arrays.stream(rawHeaders)
                .map(String::trim)
                .collect(Collectors.toList());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("rowCount", dataRows.size());
        metadata.put("columnNames", columnNames);
        metadata.put(PROPOSALS_METADATA_KEY, proposals);

        return new RawContent(
            file.getFileName().toString(),
            "text/csv",
            sb.toString(),
            size,
            metadata
        );
    }

    private ManualProcessProposal parseProposal(String[] row, Map<String, Integer> headerIndex) {
        String name = getField(row, headerIndex, "name");
        String description = getField(row, headerIndex, "description");
        String classification = getField(row, headerIndex, "classification");
        String frequency = getField(row, headerIndex, "frequency");
        String primaryOwner = getField(row, headerIndex, "primary_owner");
        String businessLine = getField(row, headerIndex, "business_line");
        String defenseLine = getField(row, headerIndex, "defense_line");
        String documentationQuality = getField(row, headerIndex, "documentation_quality");
        String estimatedFteStr = getField(row, headerIndex, "estimated_fte");
        String riskRating = getField(row, headerIndex, "risk_rating");
        String linkedSystemsStr = getField(row, headerIndex, "linked_systems");
        String participantsStr = getField(row, headerIndex, "participants");
        String notes = getField(row, headerIndex, "notes");

        Double estimatedFte = null;
        if (!estimatedFteStr.isBlank()) {
            try {
                estimatedFte = Double.parseDouble(estimatedFteStr.trim());
            } catch (NumberFormatException e) {
                log.warn("Could not parse estimated_fte value: '{}'", estimatedFteStr);
            }
        }

        List<ManualProcessProposal.SystemLinkProposal> systemLinks = parseSystemLinks(linkedSystemsStr);
        List<ManualProcessProposal.RoleProposal> roles = parseParticipants(participantsStr);

        return new ManualProcessProposal(
            name, description, classification, frequency,
            primaryOwner, businessLine, defenseLine, documentationQuality,
            estimatedFte, riskRating, systemLinks, roles, notes
        );
    }

    private String getField(String[] row, Map<String, Integer> headerIndex, String columnName) {
        Integer idx = headerIndex.get(columnName);
        if (idx == null || idx >= row.length) {
            return "";
        }
        return row[idx].trim();
    }

    /**
     * Parses pipe-delimited system links, e.g. {@code Archer:COMPENSATES_FOR|IssuesPortal:BRIDGES_FROM}
     */
    private List<ManualProcessProposal.SystemLinkProposal> parseSystemLinks(String value) {
        if (value == null || value.isBlank()) {
            return Collections.emptyList();
        }
        List<ManualProcessProposal.SystemLinkProposal> links = new ArrayList<>();
        for (String entry : value.split("\\|")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            int colonIdx = trimmed.indexOf(':');
            if (colonIdx > 0 && colonIdx < trimmed.length() - 1) {
                links.add(new ManualProcessProposal.SystemLinkProposal(
                    trimmed.substring(0, colonIdx).trim(),
                    trimmed.substring(colonIdx + 1).trim()
                ));
            } else {
                log.warn("Malformed system link entry: '{}'", trimmed);
            }
        }
        return links;
    }

    /**
     * Parses pipe-delimited participants, e.g. {@code J Smith:OWNER|M Lee:EXECUTOR}
     */
    private List<ManualProcessProposal.RoleProposal> parseParticipants(String value) {
        if (value == null || value.isBlank()) {
            return Collections.emptyList();
        }
        List<ManualProcessProposal.RoleProposal> roles = new ArrayList<>();
        for (String entry : value.split("\\|")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            int colonIdx = trimmed.indexOf(':');
            if (colonIdx > 0 && colonIdx < trimmed.length() - 1) {
                roles.add(new ManualProcessProposal.RoleProposal(
                    trimmed.substring(0, colonIdx).trim(),
                    trimmed.substring(colonIdx + 1).trim()
                ));
            } else {
                log.warn("Malformed participant entry: '{}'", trimmed);
            }
        }
        return roles;
    }
}
