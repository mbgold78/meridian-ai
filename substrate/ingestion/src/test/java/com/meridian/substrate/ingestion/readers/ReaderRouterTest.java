package com.meridian.substrate.ingestion.readers;

import com.meridian.substrate.ingestion.config.IngestionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the routing fix (port-map §2.4): manual-process CSVs and workflow text
 * files MUST reach their dedicated readers — in homepoc both were unreachable
 * dead code behind virtual extensions.
 */
class ReaderRouterTest {

    private ReaderRouter router;

    @BeforeEach
    void setUp() {
        List<FileReader> readers = List.of(
                new CsvFileReader(),
                new JsonFileReader(),
                new TextFileReader(),
                new ManualProcessCsvReader(),
                new WorkflowTextReader());
        router = new ReaderRouter(readers, new IngestionProperties());
    }

    @Test
    void routesManualProcessCsvToDedicatedReader() {
        RoutedDocument routed = router.route("glacier-manual-processes.csv");
        assertThat(routed.tag()).isEqualTo(DocumentTag.MANUAL_PROCESS_CSV);
        assertThat(routed.reader()).isInstanceOf(ManualProcessCsvReader.class);
    }

    @Test
    void routesWorkflowMarkdownToWorkflowReader() {
        RoutedDocument routed = router.route("glacier-workflow-notes.md");
        assertThat(routed.tag()).isEqualTo(DocumentTag.WORKFLOW_TEXT);
        assertThat(routed.reader()).isInstanceOf(WorkflowTextReader.class);
    }

    @Test
    void routesSystemCsvToPlainCsvReader() {
        RoutedDocument routed = router.route("glacier-systems.csv");
        assertThat(routed.tag()).isEqualTo(DocumentTag.SYSTEM_CSV);
        assertThat(routed.reader()).isInstanceOf(CsvFileReader.class);
    }

    @Test
    void routesEntityCsv() {
        assertThat(router.classify("glacier-entities.csv")).isEqualTo(DocumentTag.ENTITY_CSV);
    }

    @Test
    void fallsBackToExtensionDispatch() {
        assertThat(router.classify("misc-data.csv")).isEqualTo(DocumentTag.GENERIC_CSV);
        assertThat(router.classify("notes.txt")).isEqualTo(DocumentTag.GENERIC_TEXT);
        assertThat(router.classify("export.json")).isEqualTo(DocumentTag.GENERIC_JSON);
    }

    @Test
    void unsupportedExtensionIsNotRoutable() {
        RoutedDocument routed = router.route("photo.png");
        assertThat(routed.tag()).isEqualTo(DocumentTag.UNSUPPORTED);
        assertThat(routed.isSupported()).isFalse();
    }
}
