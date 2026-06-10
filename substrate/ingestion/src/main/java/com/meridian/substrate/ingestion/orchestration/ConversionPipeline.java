package com.meridian.substrate.ingestion.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.ai.config.LlmProperties;
import com.meridian.substrate.common.domain.DocumentRegistry;
import com.meridian.substrate.common.domain.IngestRun;
import com.meridian.substrate.common.domain.enums.DocumentType;
import com.meridian.substrate.common.domain.enums.IngestionStatus;
import com.meridian.substrate.common.repo.DocumentRegistryRepository;
import com.meridian.substrate.ingestion.audit.IngestRunRecorder;
import com.meridian.substrate.ingestion.config.IngestionProperties;
import com.meridian.substrate.ingestion.readers.DocumentTag;
import com.meridian.substrate.ingestion.readers.ManualProcessCsvReader;
import com.meridian.substrate.ingestion.readers.ManualProcessProposal;
import com.meridian.substrate.ingestion.readers.ReaderRouter;
import com.meridian.substrate.ingestion.source.DocumentRef;
import com.meridian.substrate.ingestion.source.DocumentSource;
import com.meridian.substrate.ingestion.source.RawDocument;
import com.meridian.substrate.ingestion.source.SourceQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the conversion stage: list files, route to a reader, extract
 * records (deterministic proposals path or schema-enforced LLM extraction),
 * and write per-kind entity JSON + a provenance manifest to the output dir.
 *
 * <p>Substrate rebuild of homepoc's ConversionPipeline (port-map §2.4):
 * <ul>
 *   <li>routing + prompt selection unified in {@link ReaderRouter} (configured
 *       rules, no compile-time PROMPT_MAPPINGS table);</li>
 *   <li>manual-process CSVs convert DETERMINISTICALLY from the reader's
 *       structured proposals (no LLM round-trip) into processes +
 *       process-system-links + process-roles, preserving the name references
 *       that the commit stage resolves to UUIDs;</li>
 *   <li>original source files are registered in DocumentRegistry at convert
 *       time with sha256 dedup — re-converting an already-committed file is
 *       skipped;</li>
 *   <li>output files are named {@code <basename>.<kind>.json} so the commit
 *       stage resolves the entity kind from an exact suffix instead of
 *       homepoc's buggy substring guessing;</li>
 *   <li>oversized inputs are chunked ({@link InputChunker}) and the extracted
 *       record arrays merged;</li>
 *   <li>an {@link IngestRun} audit row is written per run.</li>
 * </ul>
 */
@Service
public class ConversionPipeline {

    private static final Logger log = LoggerFactory.getLogger(ConversionPipeline.class);

    /** Output entity kinds — the commit stage's vocabulary. */
    public static final String KIND_SYSTEMS = "systems";
    public static final String KIND_PROCESSES = "processes";
    public static final String KIND_CANONICAL_ENTITIES = "canonical-entities";
    public static final String KIND_ORGANIZATION_MEMBERS = "organization-members";
    public static final String KIND_PROCESS_SYSTEM_LINKS = "process-system-links";
    public static final String KIND_PROCESS_ROLES = "process-roles";
    public static final String KIND_FINDINGS = "findings";

    private final DocumentSource documentSource;
    private final ReaderRouter readerRouter;
    private final PromptLoader promptLoader;
    private final StructuredExtractor structuredExtractor;
    private final InputChunker inputChunker;
    private final IngestRunRecorder ingestRunRecorder;
    private final DocumentRegistryRepository documentRegistryRepository;
    private final IngestionProperties ingestionProperties;
    private final LlmProperties llmProperties;
    private final ObjectMapper objectMapper;

    public ConversionPipeline(DocumentSource documentSource,
                              ReaderRouter readerRouter,
                              PromptLoader promptLoader,
                              StructuredExtractor structuredExtractor,
                              InputChunker inputChunker,
                              IngestRunRecorder ingestRunRecorder,
                              DocumentRegistryRepository documentRegistryRepository,
                              IngestionProperties ingestionProperties,
                              LlmProperties llmProperties,
                              ObjectMapper objectMapper) {
        this.documentSource = documentSource;
        this.readerRouter = readerRouter;
        this.promptLoader = promptLoader;
        this.structuredExtractor = structuredExtractor;
        this.inputChunker = inputChunker;
        this.ingestRunRecorder = ingestRunRecorder;
        this.documentRegistryRepository = documentRegistryRepository;
        this.ingestionProperties = ingestionProperties;
        this.llmProperties = llmProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * Convert every supported file under {@code inputDir} into per-kind entity
     * JSON files under {@code outputDir}.
     */
    public ConversionResult convertAll(Path inputDir, Path outputDir) {
        log.info("Starting conversion: input={}, output={}", inputDir.toAbsolutePath(), outputDir.toAbsolutePath());
        ensureDirectory(outputDir);

        IngestRun run = ingestRunRecorder.start("convert", inputDir.toString(), null);

        List<DocumentRef> documents = documentSource.list(
                new SourceQuery(inputDir.toString(), List.of(), "local-file"));
        log.info("Found {} candidate documents", documents.size());

        int filesProcessed = 0;
        int filesAlreadyIngested = 0;
        int filesUnsupported = 0;
        int entitiesExtracted = 0;
        Map<String, Integer> kindCounts = new LinkedHashMap<>();
        List<String> errorMessages = new ArrayList<>();
        List<ConversionManifest.Entry> manifestEntries = new ArrayList<>();

        for (DocumentRef ref : documents) {
            Path sourcePath = Path.of(ref.id());
            String fileName = ref.displayName();
            try {
                String sha256 = computeSha256(sourcePath);

                DocumentRegistry existing = documentRegistryRepository
                        .findBySourceFileSha256(sha256).orElse(null);
                if (existing != null && existing.getProcessedByConverterRunId() != null) {
                    log.info("Skipping already-ingested source (sha256 match): {}", fileName);
                    filesAlreadyIngested++;
                    continue;
                }

                var routed = readerRouter.route(fileName);
                if (!routed.isSupported()) {
                    log.warn("Unsupported file type, skipping: {} (tag={})", fileName, routed.tag());
                    filesUnsupported++;
                    continue;
                }

                RawDocument rawDoc = documentSource.fetch(ref);
                if (rawDoc.metadata() != null && rawDoc.metadata().containsKey("error")) {
                    throw new IOException(String.valueOf(rawDoc.metadata().get("error")));
                }
                if (rawDoc.extractedText() == null || rawDoc.extractedText().isBlank()) {
                    log.warn("Empty content, skipping: {}", fileName);
                    filesUnsupported++;
                    continue;
                }

                DocumentRegistry registryRow = existing != null
                        ? existing
                        : registerSourceDocument(sourcePath, fileName, sha256, routed.tag());

                Map<String, ArrayNode> outputs = convertDocument(routed.tag(), rawDoc);

                int fileEntityCount = 0;
                for (Map.Entry<String, ArrayNode> output : outputs.entrySet()) {
                    String kind = output.getKey();
                    ArrayNode records = output.getValue();
                    if (records.isEmpty()) {
                        continue;
                    }
                    String outputFileName = deriveOutputFileName(fileName, kind);
                    writeJson(outputDir.resolve(outputFileName), records);
                    fileEntityCount += records.size();
                    kindCounts.merge(kind, records.size(), Integer::sum);
                    manifestEntries.add(new ConversionManifest.Entry(
                            outputFileName,
                            fileName,
                            sha256,
                            registryRow.getId(),
                            routed.tag().name(),
                            readerRouter.promptTemplateFor(routed.tag()),
                            kind,
                            records.size()));
                    log.info("Wrote {} {} records to {}", records.size(), kind, outputFileName);
                }

                filesProcessed++;
                entitiesExtracted += fileEntityCount;
            } catch (Exception e) {
                String msg = "Failed to convert " + fileName + ": " + e.getMessage();
                log.error(msg, e);
                errorMessages.add(msg);
            }
        }

        if (!manifestEntries.isEmpty()) {
            try {
                writeJson(outputDir.resolve(ConversionManifest.FILE_NAME),
                        objectMapper.valueToTree(new ConversionManifest(manifestEntries)));
            } catch (IOException e) {
                errorMessages.add("Failed to write manifest: " + e.getMessage());
            }
        }

        IngestRun finished = ingestRunRecorder.finish(run,
                filesProcessed,
                filesAlreadyIngested + filesUnsupported,
                entitiesExtracted,
                0,
                kindCounts,
                errorMessages);

        log.info("Conversion complete: {} files processed, {} already ingested, {} unsupported, "
                        + "{} entities extracted, {} errors",
                filesProcessed, filesAlreadyIngested, filesUnsupported,
                entitiesExtracted, errorMessages.size());

        return new ConversionResult(filesProcessed, filesAlreadyIngested, filesUnsupported,
                entitiesExtracted, errorMessages.size(), errorMessages, finished.getId());
    }

    /**
     * Convert one routed document into per-kind record arrays.
     */
    private Map<String, ArrayNode> convertDocument(DocumentTag tag, RawDocument rawDoc) {
        if (tag == DocumentTag.MANUAL_PROCESS_CSV) {
            return convertProposals(rawDoc);
        }

        String template = readerRouter.promptTemplateFor(tag);
        if (template == null) {
            throw new IllegalStateException("No prompt template configured for tag " + tag);
        }

        String model = llmProperties.getAnthropic().getModel();
        int maxTokens = llmProperties.getAnthropic().getMaxTokens();

        ArrayNode merged = objectMapper.createArrayNode();
        List<String> chunks = inputChunker.chunk(rawDoc.extractedText(),
                ingestionProperties.getMaxInputChars());
        for (String chunk : chunks) {
            String prompt = promptLoader.loadPrompt(template, Map.of("input_data", chunk));
            ArrayNode records = structuredExtractor.extractRecords(model, prompt, maxTokens);
            merged.addAll(records);
        }

        return Map.of(kindForTag(tag), merged);
    }

    /**
     * Deterministic conversion of the manual-process CSV reader's structured
     * proposals — no LLM round-trip. Link/role records carry NAME references
     * (processName/systemName/memberName) that commit resolves to UUIDs.
     */
    @SuppressWarnings("unchecked")
    private Map<String, ArrayNode> convertProposals(RawDocument rawDoc) {
        Object raw = rawDoc.metadata() != null
                ? rawDoc.metadata().get(ManualProcessCsvReader.PROPOSALS_METADATA_KEY)
                : null;
        List<ManualProcessProposal> proposals = raw instanceof List<?>
                ? (List<ManualProcessProposal>) raw
                : List.of();

        ArrayNode processes = objectMapper.createArrayNode();
        ArrayNode links = objectMapper.createArrayNode();
        ArrayNode roles = objectMapper.createArrayNode();

        for (ManualProcessProposal proposal : proposals) {
            if (proposal.name() == null || proposal.name().isBlank()) {
                continue;
            }
            ObjectNode process = processes.addObject();
            process.put("name", proposal.name());
            putIfPresent(process, "description", proposal.description());
            putIfPresent(process, "classification", proposal.classification());
            putIfPresent(process, "frequency", proposal.frequency());
            putIfPresent(process, "primaryOwner", proposal.primaryOwner());
            putIfPresent(process, "businessLine", proposal.businessLine());
            putIfPresent(process, "defenseLine", proposal.defenseLine());
            putIfPresent(process, "documentationQuality", proposal.documentationQuality());
            putIfPresent(process, "riskRating", proposal.riskRating());
            putIfPresent(process, "notes", proposal.notes());
            if (proposal.estimatedFte() != null) {
                process.put("estimatedFte", proposal.estimatedFte());
            }

            for (ManualProcessProposal.SystemLinkProposal link : proposal.systemLinks()) {
                ObjectNode node = links.addObject();
                node.put("processName", proposal.name());
                node.put("systemName", link.systemName());
                node.put("linkType", link.linkType());
            }
            for (ManualProcessProposal.RoleProposal role : proposal.roles()) {
                ObjectNode node = roles.addObject();
                node.put("processName", proposal.name());
                node.put("memberName", role.memberName());
                node.put("participation", role.participation());
            }
        }

        Map<String, ArrayNode> outputs = new LinkedHashMap<>();
        outputs.put(KIND_PROCESSES, processes);
        outputs.put(KIND_PROCESS_SYSTEM_LINKS, links);
        outputs.put(KIND_PROCESS_ROLES, roles);
        return outputs;
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }

    private String kindForTag(DocumentTag tag) {
        return switch (tag) {
            case SYSTEM_CSV -> KIND_SYSTEMS;
            case ENTITY_CSV, GENERIC_CSV, GENERIC_JSON -> KIND_CANONICAL_ENTITIES;
            case WORKFLOW_TEXT, GENERIC_TEXT, MANUAL_PROCESS_CSV -> KIND_PROCESSES;
            case UNSUPPORTED -> throw new IllegalArgumentException("UNSUPPORTED tag has no kind");
        };
    }

    private DocumentRegistry registerSourceDocument(Path sourcePath, String fileName,
                                                    String sha256, DocumentTag tag) throws IOException {
        DocumentRegistry doc = new DocumentRegistry();
        doc.setSourceFile(fileName);
        doc.setSourceFileSha256(sha256);
        doc.setSourceFileSize(Files.size(sourcePath));
        doc.setDocumentType(tag == DocumentTag.WORKFLOW_TEXT || tag == DocumentTag.GENERIC_TEXT
                ? DocumentType.PROCESS_DOC
                : DocumentType.SAMPLE_DATA);
        doc.setSourceSystem("ingestion");
        doc.setIngestedToTachyon(false);
        doc.setIngestionStatus(IngestionStatus.LOCAL_ONLY);
        doc.setCreatedBy("ingestion");
        return documentRegistryRepository.save(doc);
    }

    /** Output naming contract: {@code <basename>.<kind>.json} — commit resolves kind by exact suffix. */
    private String deriveOutputFileName(String inputFileName, String kind) {
        String baseName = inputFileName;
        int dot = baseName.lastIndexOf('.');
        if (dot >= 0) {
            baseName = baseName.substring(0, dot);
        }
        return baseName + "." + kind + ".json";
    }

    private void writeJson(Path file, JsonNode node) throws IOException {
        Files.writeString(file, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node));
    }

    static String computeSha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JVM spec — this is unreachable on a compliant JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void ensureDirectory(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create output directory: " + dir, e);
        }
    }
}
