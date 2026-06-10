package com.meridian.substrate.ingestion.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.ingestion.commit.CommitResult;
import com.meridian.substrate.ingestion.commit.CommitService;
import com.meridian.substrate.ingestion.config.IngestionProperties;
import com.meridian.substrate.ingestion.orchestration.ConversionPipeline;
import com.meridian.substrate.ingestion.orchestration.ConversionResult;
import com.meridian.substrate.ingestion.staging.StagingResult;
import com.meridian.substrate.ingestion.staging.StagingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CLI entry point: {@code convert | stage | commit | convert-and-stage}.
 *
 * <p>Substrate rebuild of homepoc's ConverterCommand with the port-map §2.4
 * CLI fixes:
 * <ul>
 *   <li><b>Non-zero exit codes</b> — {@link ExitCodeGenerator}; homepoc always
 *       exited 0. Codes: 0 success · 1 errors during processing · 2 usage
 *       error · 3 zero-yield hard fail.</li>
 *   <li><b>No silent zero-yield</b> — a run that consumed NOTHING (no files
 *       converted/staged/committed and none legitimately deduplicated) is a
 *       HARD FAIL with a machine-readable {@code INGEST_EVENT} line and exit
 *       code {@value #EXIT_ZERO_YIELD}. A fully-deduplicated re-run (all input
 *       sha256-matched as already ingested) is a legitimate no-op success.</li>
 *   <li>Structured {@code INGEST_EVENT} summary line per stage (greppable from
 *       wrappers/CI).</li>
 * </ul>
 *
 * <p>Flags: {@code --input <dir>}, {@code --output <dir>}, {@code --staging <dir>},
 * {@code --domain <name>} — both {@code --flag value} and {@code --flag=value}
 * forms. Dotted {@code --key=value} args (Spring property overrides such as
 * {@code --substrate.llm.provider=mock}) are ignored here; Spring binds them.
 */
@Component
public class IngestionCommand implements CommandLineRunner, ExitCodeGenerator {

    public static final int EXIT_OK = 0;
    public static final int EXIT_ERRORS = 1;
    public static final int EXIT_USAGE = 2;
    public static final int EXIT_ZERO_YIELD = 3;

    private static final Logger log = LoggerFactory.getLogger(IngestionCommand.class);

    private final ConversionPipeline conversionPipeline;
    private final StagingService stagingService;
    private final CommitService commitService;
    private final IngestionProperties properties;
    private final ObjectMapper objectMapper;

    private int exitCode = EXIT_OK;

    public IngestionCommand(ConversionPipeline conversionPipeline,
                            StagingService stagingService,
                            CommitService commitService,
                            IngestionProperties properties,
                            ObjectMapper objectMapper) {
        this.conversionPipeline = conversionPipeline;
        this.stagingService = stagingService;
        this.commitService = commitService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(String... args) {
        if (!properties.getCli().isEnabled()) {
            log.debug("Ingestion CLI disabled (substrate.ingestion.cli.enabled=false)");
            return;
        }
        try {
            exitCode = execute(args);
        } catch (Exception e) {
            log.error("Ingestion run failed: {}", e.getMessage(), e);
            emitEvent("run_failed", Map.of("error", String.valueOf(e.getMessage())));
            exitCode = EXIT_ERRORS;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    /**
     * Parse args and dispatch. Public so tests can drive the CLI directly and
     * assert the returned exit code without booting a fresh JVM.
     */
    public int execute(String... args) {
        ParsedArgs parsed = parseArgs(args);
        if (parsed.command() == null) {
            printUsage();
            return EXIT_USAGE;
        }

        return switch (parsed.command()) {
            case "convert" -> runConvert(parsed, false);
            case "stage" -> runStage(parsed);
            case "commit" -> runCommit(parsed);
            case "convert-and-stage" -> runConvert(parsed, true);
            default -> {
                System.err.println("Unknown command: " + parsed.command());
                printUsage();
                yield EXIT_USAGE;
            }
        };
    }

    private int runConvert(ParsedArgs parsed, boolean alsoStage) {
        Path inputDir = parsed.dir("input", properties.getSamplesDir());
        Path outputDir = parsed.dir("output", properties.getOutputDir());
        if (!Files.isDirectory(inputDir)) {
            System.err.println("Input directory does not exist: " + inputDir.toAbsolutePath());
            return EXIT_USAGE;
        }

        ConversionResult result = conversionPipeline.convertAll(inputDir, outputDir);
        emitEvent("convert_complete", Map.of(
                "inputDir", inputDir.toString(),
                "outputDir", outputDir.toString(),
                "filesProcessed", result.filesProcessed(),
                "filesAlreadyIngested", result.filesAlreadyIngested(),
                "filesUnsupported", result.filesUnsupported(),
                "entitiesExtracted", result.entitiesExtracted(),
                "errors", result.errors(),
                "ingestRunId", String.valueOf(result.ingestRunId())));

        boolean zeroYield = result.filesProcessed() == 0 && result.filesAlreadyIngested() == 0;
        if (zeroYield) {
            emitEvent("zero_yield", Map.of(
                    "command", alsoStage ? "convert-and-stage" : "convert",
                    "inputDir", inputDir.toString(),
                    "filesUnsupported", result.filesUnsupported(),
                    "errors", result.errors(),
                    "reason", "no input file produced any output"));
            return EXIT_ZERO_YIELD;
        }
        if (result.errors() > 0) {
            result.errorMessages().forEach(m -> System.err.println("ERROR: " + m));
            return EXIT_ERRORS;
        }
        if (!alsoStage) {
            return EXIT_OK;
        }

        if (result.filesProcessed() == 0) {
            // Everything already ingested — nothing new to stage; legitimate no-op.
            log.info("All inputs already ingested (sha256 match) — nothing new to stage.");
            return EXIT_OK;
        }
        return stageDirectory(outputDir, parsed);
    }

    private int runStage(ParsedArgs parsed) {
        Path outputDir = parsed.dir("output", properties.getOutputDir());
        return stageDirectory(outputDir, parsed);
    }

    private int stageDirectory(Path outputDir, ParsedArgs parsed) {
        Path stagingDir = parsed.dir("staging", properties.getStagingDir());
        StagingResult result = stagingService.stage(outputDir, stagingDir);
        emitEvent("stage_complete", Map.of(
                "outputDir", outputDir.toString(),
                "stagingDir", result.stagingDir(),
                "filesStaged", result.filesStaged()));
        if (result.filesStaged() == 0) {
            emitEvent("zero_yield", Map.of(
                    "command", "stage",
                    "outputDir", outputDir.toString(),
                    "reason", "no JSON files found to stage"));
            return EXIT_ZERO_YIELD;
        }
        return EXIT_OK;
    }

    private int runCommit(ParsedArgs parsed) {
        Path stagingDir = parsed.dir("input",
                parsed.values().getOrDefault("staging", properties.getStagingDir()));
        if (!Files.isDirectory(stagingDir)) {
            System.err.println("Staging directory does not exist: " + stagingDir.toAbsolutePath());
            return EXIT_USAGE;
        }

        CommitResult result = commitService.commit(stagingDir, parsed.values().get("domain"));
        Map<String, Object> event = new HashMap<>(Map.of(
                "stagingDir", stagingDir.toString(),
                "filesCommitted", result.filesCommitted(),
                "filesAlreadyCommitted", result.filesAlreadyCommitted(),
                "filesUnrecognized", result.filesUnrecognized(),
                "entitiesCommitted", result.entitiesCommitted(),
                "entitiesUpdated", result.entitiesUpdated(),
                "errors", result.errors(),
                "ingestRunId", String.valueOf(result.ingestRunId())));
        event.put("entityCounts", result.entityCounts());
        emitEvent("commit_complete", event);

        boolean zeroYield = result.filesCommitted() == 0 && result.filesAlreadyCommitted() == 0;
        if (zeroYield) {
            emitEvent("zero_yield", Map.of(
                    "command", "commit",
                    "stagingDir", stagingDir.toString(),
                    "filesUnrecognized", result.filesUnrecognized(),
                    "errors", result.errors(),
                    "reason", "no staged file was committed or matched as already committed"));
            return EXIT_ZERO_YIELD;
        }
        if (result.errors() > 0) {
            result.errorMessages().forEach(m -> System.err.println("ERROR: " + m));
            return EXIT_ERRORS;
        }
        return EXIT_OK;
    }

    /** Single-line machine-readable event: {@code INGEST_EVENT {"event":...,...}}. */
    private void emitEvent(String event, Map<String, ?> fields) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("event", event);
        fields.forEach((k, v) -> node.set(k, objectMapper.valueToTree(v)));
        System.out.println("INGEST_EVENT " + node);
    }

    private ParsedArgs parseArgs(String... args) {
        Map<String, String> values = new HashMap<>();
        String command = null;
        List<String> positional = new java.util.ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--")) {
                String body = arg.substring(2);
                int eq = body.indexOf('=');
                if (eq >= 0) {
                    String key = body.substring(0, eq);
                    if (!key.contains(".")) { // dotted keys are Spring property overrides
                        values.put(key, body.substring(eq + 1));
                    }
                } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    values.put(body, args[++i]);
                } else {
                    values.put(body, "");
                }
            } else if (command == null) {
                command = arg;
            } else {
                positional.add(arg);
            }
        }

        // Positional fallbacks, homepoc-compatible: convert <input> <output>, commit <staging>.
        if (command != null && !positional.isEmpty()) {
            switch (command) {
                case "convert", "convert-and-stage" -> {
                    values.putIfAbsent("input", positional.get(0));
                    if (positional.size() > 1) {
                        values.putIfAbsent("output", positional.get(1));
                    }
                }
                case "stage" -> values.putIfAbsent("output", positional.get(0));
                case "commit" -> values.putIfAbsent("input", positional.get(0));
                default -> { /* unknown command handled by dispatch */ }
            }
        }
        return new ParsedArgs(command, values);
    }

    private void printUsage() {
        System.out.println("Usage: substrate-ingestion <command> [options]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  convert            Convert source files to entity JSON");
        System.out.println("  stage              Stage converted output for review");
        System.out.println("  commit             Commit staged entities to the database");
        System.out.println("  convert-and-stage  Convert and stage in one step");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --input <dir>      Input directory (convert: source files; commit: staging dir)");
        System.out.println("  --output <dir>     Output directory for converted entity JSON");
        System.out.println("  --staging <dir>    Staging directory");
        System.out.println("  --domain <name>    Target domain name for commit (default: "
                + properties.getDomainName() + ")");
        System.out.println();
        System.out.println("Exit codes: 0 success, 1 errors, 2 usage, 3 zero-yield (hard fail)");
        System.out.println();
        System.out.println("Example (mock LLM provider, no API key needed):");
        System.out.println("  gradlew :ingestion:bootRun --args=\"convert-and-stage --input samples"
                + " --substrate.llm.provider=mock\"");
        System.out.println("  gradlew :ingestion:bootRun --args=\"commit\"");
    }

    private record ParsedArgs(String command, Map<String, String> values) {
        Path dir(String key, String defaultValue) {
            return Path.of(values.getOrDefault(key, defaultValue));
        }
    }
}
