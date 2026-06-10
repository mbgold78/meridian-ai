package com.meridian.substrate.ingestion.readers;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reads .csv files using opencsv and converts each row to a header: value
 * text representation suitable for downstream LLM processing.
 */
@Component
public class CsvFileReader implements FileReader {

    @Override
    public String readerId() {
        return "csv";
    }

    @Override
    public RawContent read(Path file) throws IOException {
        long size = Files.size(file);

        List<String[]> allRows;
        try (CSVReader reader = new CSVReader(Files.newBufferedReader(file))) {
            allRows = reader.readAll();
        } catch (CsvException e) {
            throw new IOException("Failed to parse CSV: " + file, e);
        }

        if (allRows.isEmpty()) {
            return new RawContent(
                file.getFileName().toString(),
                "text/csv",
                "",
                size,
                Map.of("rowCount", 0, "columnNames", List.of())
            );
        }

        String[] headers = allRows.get(0);
        List<String[]> dataRows = allRows.subList(1, allRows.size());

        StringBuilder sb = new StringBuilder();
        for (int rowIdx = 0; rowIdx < dataRows.size(); rowIdx++) {
            String[] row = dataRows.get(rowIdx);
            sb.append("--- Row ").append(rowIdx + 1).append(" ---\n");
            for (int col = 0; col < headers.length; col++) {
                String value = col < row.length ? row[col].trim() : "";
                sb.append(headers[col].trim()).append(": ").append(value).append("\n");
            }
            sb.append("\n");
        }

        List<String> columnNames = Arrays.stream(headers)
                .map(String::trim)
                .collect(Collectors.toList());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("rowCount", dataRows.size());
        metadata.put("columnNames", columnNames);

        return new RawContent(
            file.getFileName().toString(),
            "text/csv",
            sb.toString(),
            size,
            metadata
        );
    }
}
