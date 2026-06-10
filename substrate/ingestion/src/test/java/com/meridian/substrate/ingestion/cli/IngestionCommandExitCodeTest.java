package com.meridian.substrate.ingestion.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the CLI exit-code contract (port-map §2.4: "non-zero exit codes on
 * failure; HARD FAIL on zero-yield with structured event line + distinct exit
 * code" — homepoc always exited 0).
 */
@SpringBootTest(properties =
        // Own H2 database: zero-yield commits still write IngestRun/Domain rows,
        // which must not bleed into the E2E test's row-count assertions.
        "spring.datasource.url=jdbc:h2:mem:ingestion-cli-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class IngestionCommandExitCodeTest {

    @Autowired
    private IngestionCommand command;

    @TempDir
    Path emptyDir;

    @TempDir
    Path outputDir;

    @Test
    void convertOverEmptyDirectoryIsZeroYieldHardFail(CapturedOutput output) {
        int exitCode = command.execute("convert",
                "--input", emptyDir.toString(),
                "--output", outputDir.toString());
        assertThat(exitCode).isEqualTo(IngestionCommand.EXIT_ZERO_YIELD);
        assertThat(output.getOut())
                .contains("INGEST_EVENT")
                .contains("\"event\":\"zero_yield\"")
                .contains("\"command\":\"convert\"");
    }

    @Test
    void commitOverEmptyStagingDirectoryIsZeroYieldHardFail(CapturedOutput output) {
        int exitCode = command.execute("commit", "--input", emptyDir.toString());
        assertThat(exitCode).isEqualTo(IngestionCommand.EXIT_ZERO_YIELD);
        assertThat(output.getOut())
                .contains("\"event\":\"zero_yield\"")
                .contains("\"command\":\"commit\"");
    }

    @Test
    void stageWithNothingToStageIsZeroYieldHardFail(CapturedOutput output) {
        int exitCode = command.execute("stage",
                "--output", emptyDir.toString(),
                "--staging", outputDir.resolve("staging").toString());
        assertThat(exitCode).isEqualTo(IngestionCommand.EXIT_ZERO_YIELD);
        assertThat(output.getOut()).contains("\"event\":\"zero_yield\"");
    }

    @Test
    void unknownCommandIsUsageError() {
        assertThat(command.execute("frobnicate")).isEqualTo(IngestionCommand.EXIT_USAGE);
    }

    @Test
    void missingCommandIsUsageError() {
        assertThat(command.execute()).isEqualTo(IngestionCommand.EXIT_USAGE);
    }

    @Test
    void missingInputDirectoryIsUsageError() {
        assertThat(command.execute("convert", "--input", emptyDir.resolve("nope").toString()))
                .isEqualTo(IngestionCommand.EXIT_USAGE);
    }
}
