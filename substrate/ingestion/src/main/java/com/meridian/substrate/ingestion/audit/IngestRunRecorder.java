package com.meridian.substrate.ingestion.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.meridian.substrate.common.domain.IngestRun;
import com.meridian.substrate.common.repo.IngestRunRepository;
import com.meridian.substrate.ingestion.config.IngestionProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes IngestRun audit rows — one per pipeline run (convert or commit).
 * Rows are written eagerly (RUNNING) and finalized at the end so a crashed
 * run leaves a visible RUNNING row rather than no trace.
 */
@Service
public class IngestRunRecorder {

    private final IngestRunRepository ingestRunRepository;
    private final IngestionProperties properties;
    private final ObjectMapper objectMapper;

    public IngestRunRecorder(IngestRunRepository ingestRunRepository,
                             IngestionProperties properties,
                             ObjectMapper objectMapper) {
        this.ingestRunRepository = ingestRunRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Open a new RUNNING audit row. */
    public IngestRun start(String command, String sourceDir, UUID domainId) {
        IngestRun run = new IngestRun();
        run.setCommand(command);
        run.setSourceDir(sourceDir);
        run.setDomainId(domainId);
        run.setStatus(IngestRun.STATUS_RUNNING);
        run.setStartedAt(Instant.now());
        run.setCreatedBy("ingestion");
        return ingestRunRepository.save(run);
    }

    /** Finalize an audit row with outcome counts; status is derived from yield/errors. */
    public IngestRun finish(IngestRun run,
                            int filesProcessed,
                            int filesSkipped,
                            int entitiesCommitted,
                            int entitiesUpdated,
                            Map<String, Integer> entityCounts,
                            List<String> errorMessages) {
        run.setFinishedAt(Instant.now());
        run.setFilesProcessed(filesProcessed);
        run.setFilesSkipped(filesSkipped);
        run.setEntitiesCommitted(entitiesCommitted);
        run.setEntitiesUpdated(entitiesUpdated);
        run.setErrorCount(errorMessages.size());
        run.setEntityCounts(objectMapper.valueToTree(entityCounts));

        ArrayNode errors = objectMapper.createArrayNode();
        errorMessages.stream()
                .limit(properties.getMaxPersistedErrors())
                .forEach(errors::add);
        run.setErrors(errors);

        boolean anyYield = entitiesCommitted + entitiesUpdated > 0;
        if (errorMessages.isEmpty()) {
            run.setStatus(IngestRun.STATUS_SUCCEEDED);
        } else if (anyYield) {
            run.setStatus(IngestRun.STATUS_PARTIAL);
        } else {
            run.setStatus(IngestRun.STATUS_FAILED);
        }
        return ingestRunRepository.save(run);
    }
}
