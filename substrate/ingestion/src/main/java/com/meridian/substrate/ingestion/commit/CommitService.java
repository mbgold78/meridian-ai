package com.meridian.substrate.ingestion.commit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.CanonicalEntity;
import com.meridian.substrate.common.domain.DocumentRegistry;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.domain.Evidence;
import com.meridian.substrate.common.domain.Finding;
import com.meridian.substrate.common.domain.IngestRun;
import com.meridian.substrate.common.domain.OrganizationMember;
import com.meridian.substrate.common.domain.Process;
import com.meridian.substrate.common.domain.ProcessRole;
import com.meridian.substrate.common.domain.ProcessSystemLink;
import com.meridian.substrate.common.domain.SystemEntity;
import com.meridian.substrate.common.domain.enums.DefenseLine;
import com.meridian.substrate.common.domain.enums.DocumentType;
import com.meridian.substrate.common.domain.enums.DocumentationQuality;
import com.meridian.substrate.common.domain.enums.ExecutionMode;
import com.meridian.substrate.common.domain.enums.FindingStatus;
import com.meridian.substrate.common.domain.enums.FindingType;
import com.meridian.substrate.common.domain.enums.IngestionStatus;
import com.meridian.substrate.common.domain.enums.LinkStatus;
import com.meridian.substrate.common.domain.enums.LinkType;
import com.meridian.substrate.common.domain.enums.ManualProcessClassification;
import com.meridian.substrate.common.domain.enums.Participation;
import com.meridian.substrate.common.domain.enums.RiskRating;
import com.meridian.substrate.common.domain.enums.Severity;
import com.meridian.substrate.common.repo.CanonicalEntityRepository;
import com.meridian.substrate.common.repo.DocumentRegistryRepository;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.common.repo.EvidenceRepository;
import com.meridian.substrate.common.repo.FindingRepository;
import com.meridian.substrate.common.repo.IngestRunRepository;
import com.meridian.substrate.common.repo.OrganizationMemberRepository;
import com.meridian.substrate.common.repo.ProcessRepository;
import com.meridian.substrate.common.repo.ProcessRoleRepository;
import com.meridian.substrate.common.repo.ProcessSystemLinkRepository;
import com.meridian.substrate.common.repo.SystemRepository;
import com.meridian.substrate.ingestion.audit.IngestRunRecorder;
import com.meridian.substrate.ingestion.config.IngestionProperties;
import com.meridian.substrate.ingestion.orchestration.ConversionManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Idempotent re-ingest commit (port-map §2.4 rebuild — homepoc's CommitService
 * deserialized staged JSON straight into entities with saveAll, minting fresh
 * UUIDs on every run and never resolving name references):
 *
 * <ul>
 *   <li><b>IngestRun audit</b> — one row per commit run; staged files and the
 *       ORIGINAL source files (via the conversion manifest) are stamped with
 *       {@code DocumentRegistry.processedByConverterRunId}.</li>
 *   <li><b>sha256 skip</b> — a staged file whose sha256 matches an
 *       already-processed DocumentRegistry row is skipped wholesale, so
 *       re-running commit over the same staging dir yields identical DB row
 *       counts.</li>
 *   <li><b>Natural-key upsert</b> — entities are matched by domain-scoped
 *       natural key (domainId+name; link triples for links/roles) and updated
 *       in place instead of duplicated.</li>
 *   <li><b>name→UUID resolution</b> — link records reference systems /
 *       processes / members by NAME; names are resolved to FK UUIDs within the
 *       target domain before persisting (missing members optionally created as
 *       skeletons; missing systems/processes are recorded errors, never
 *       silently dropped rows).</li>
 *   <li><b>Entity-kind resolution</b> — manifest entityKind, exact file names,
 *       then dotted {@code .<kind>.json} suffix; the substring fallback checks
 *       longest patterns first, fixing homepoc's bug where
 *       {@code process-system-links.json} matched "system" and was routed to
 *       the system repository.</li>
 * </ul>
 */
@Service
public class CommitService {

    private static final Logger log = LoggerFactory.getLogger(CommitService.class);

    /** Commit order: referenced kinds before referencing kinds. */
    private static final List<String> KIND_ORDER = List.of(
            "systems",
            "organization-members",
            "processes",
            "canonical-entities",
            "findings",
            "process-system-links",
            "process-roles");

    /** Exact staged-file names for hand-authored staging dirs. */
    private static final Map<String, String> EXACT_FILE_KINDS = Map.of(
            "systems.json", "systems",
            "organization-members.json", "organization-members",
            "processes.json", "processes",
            "canonical-entities.json", "canonical-entities",
            "findings.json", "findings",
            "process-system-links.json", "process-system-links",
            "process-roles.json", "process-roles");

    private final ObjectMapper objectMapper;
    private final IngestionProperties properties;
    private final IngestRunRecorder ingestRunRecorder;
    private final IngestRunRepository ingestRunRepository;
    private final DomainRepository domainRepository;
    private final SystemRepository systemRepository;
    private final ProcessRepository processRepository;
    private final OrganizationMemberRepository organizationMemberRepository;
    private final CanonicalEntityRepository canonicalEntityRepository;
    private final FindingRepository findingRepository;
    private final ProcessSystemLinkRepository processSystemLinkRepository;
    private final ProcessRoleRepository processRoleRepository;
    private final DocumentRegistryRepository documentRegistryRepository;
    private final EvidenceRepository evidenceRepository;

    public CommitService(ObjectMapper objectMapper,
                         IngestionProperties properties,
                         IngestRunRecorder ingestRunRecorder,
                         IngestRunRepository ingestRunRepository,
                         DomainRepository domainRepository,
                         SystemRepository systemRepository,
                         ProcessRepository processRepository,
                         OrganizationMemberRepository organizationMemberRepository,
                         CanonicalEntityRepository canonicalEntityRepository,
                         FindingRepository findingRepository,
                         ProcessSystemLinkRepository processSystemLinkRepository,
                         ProcessRoleRepository processRoleRepository,
                         DocumentRegistryRepository documentRegistryRepository,
                         EvidenceRepository evidenceRepository) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.ingestRunRecorder = ingestRunRecorder;
        this.ingestRunRepository = ingestRunRepository;
        this.domainRepository = domainRepository;
        this.systemRepository = systemRepository;
        this.processRepository = processRepository;
        this.organizationMemberRepository = organizationMemberRepository;
        this.canonicalEntityRepository = canonicalEntityRepository;
        this.findingRepository = findingRepository;
        this.processSystemLinkRepository = processSystemLinkRepository;
        this.processRoleRepository = processRoleRepository;
        this.documentRegistryRepository = documentRegistryRepository;
        this.evidenceRepository = evidenceRepository;
    }

    /**
     * Commit all staged entity JSON under {@code stagingDir} into the target
     * domain ({@code domainNameOverride}, falling back to
     * {@code substrate.ingestion.domain-name}).
     */
    @Transactional
    public CommitResult commit(Path stagingDir, String domainNameOverride) {
        String domainName = domainNameOverride != null && !domainNameOverride.isBlank()
                ? domainNameOverride
                : properties.getDomainName();
        log.info("Committing staged entities from {} into domain '{}'",
                stagingDir.toAbsolutePath(), domainName);

        IngestRun run = ingestRunRecorder.start("commit", stagingDir.toString(), null);
        List<String> errorMessages = new ArrayList<>();

        List<Path> jsonFiles = listStagedFiles(stagingDir, errorMessages);
        ConversionManifest manifest = readManifest(stagingDir);
        Map<String, ConversionManifest.Entry> manifestByFile = new HashMap<>();
        if (manifest != null) {
            manifest.entries().forEach(e -> manifestByFile.put(e.outputFile(), e));
        }

        Domain domain = resolveDomain(domainName);
        run.setDomainId(domain.getId());

        Resolver resolver = new Resolver(domain.getId(), errorMessages);

        int filesCommitted = 0;
        int filesAlreadyCommitted = 0;
        int filesUnrecognized = 0;
        Map<String, Integer> entityCounts = new LinkedHashMap<>();
        int created = 0;
        int updated = 0;

        // Referenced kinds commit before referencing kinds, regardless of file order.
        List<StagedFile> ordered = jsonFiles.stream()
                .map(p -> new StagedFile(p, resolveEntityKind(
                        p.getFileName().toString(),
                        manifestByFile.get(p.getFileName().toString()))))
                .sorted(Comparator.comparingInt(f -> f.kind() == null
                        ? Integer.MAX_VALUE : KIND_ORDER.indexOf(f.kind())))
                .toList();

        for (StagedFile staged : ordered) {
            String fileName = staged.path().getFileName().toString();
            if (staged.kind() == null) {
                log.warn("Unrecognized entity file, skipping: {}", fileName);
                errorMessages.add("Unrecognized entity kind for staged file: " + fileName);
                filesUnrecognized++;
                continue;
            }
            try {
                String sha256 = computeSha256(staged.path());
                Optional<DocumentRegistry> existing =
                        documentRegistryRepository.findBySourceFileSha256(sha256);
                if (existing.isPresent() && existing.get().getProcessedByConverterRunId() != null) {
                    log.info("Skipping already-committed staged file (sha256 match): {}", fileName);
                    filesAlreadyCommitted++;
                    continue;
                }

                JsonNode parsed = objectMapper.readTree(Files.readAllBytes(staged.path()));
                if (!parsed.isArray()) {
                    throw new IOException("Staged file is not a JSON array of records: " + fileName);
                }

                FileOutcome outcome = commitRecords(staged.kind(), (ArrayNode) parsed,
                        domain.getId(), resolver, fileName, errorMessages);

                DocumentRegistry stagedRow = existing.orElseGet(
                        () -> registerStagedDocument(staged.path(), fileName, sha256));
                stagedRow.setProcessedByConverterRunId(run.getId());
                documentRegistryRepository.save(stagedRow);

                ConversionManifest.Entry manifestEntry = manifestByFile.get(fileName);
                UUID evidenceDocId = manifestEntry != null && manifestEntry.documentRegistryId() != null
                        ? manifestEntry.documentRegistryId()
                        : stagedRow.getId();
                String evidenceSourceFile = manifestEntry != null
                        ? manifestEntry.sourceFile() : fileName;
                writeEvidence(outcome.createdIds(), staged.kind(), evidenceSourceFile,
                        evidenceDocId, domain.getId());

                created += outcome.created();
                updated += outcome.updated();
                entityCounts.merge(staged.kind(), outcome.created() + outcome.updated(), Integer::sum);
                filesCommitted++;
                log.info("Committed {} ({} created, {} updated) from {}",
                        staged.kind(), outcome.created(), outcome.updated(), fileName);
            } catch (Exception e) {
                String msg = "Failed to commit " + fileName + ": " + e.getMessage();
                log.error(msg, e);
                errorMessages.add(msg);
            }
        }

        // Skeleton members created during role name-resolution count and leave
        // evidence under their own kind (not the roles file's kind).
        List<UUID> skeletonMembers = resolver.createdMemberIds();
        if (!skeletonMembers.isEmpty()) {
            created += skeletonMembers.size();
            entityCounts.merge("organization-members", skeletonMembers.size(), Integer::sum);
            writeEvidence(skeletonMembers, "organization-members",
                    "(skeleton — referenced by process roles in " + stagingDir.getFileName() + ")",
                    null, domain.getId());
        }

        stampManifestSources(manifestByFile, run.getId());

        IngestRun finished = ingestRunRecorder.finish(run, filesCommitted, filesAlreadyCommitted,
                created, updated, entityCounts, errorMessages);

        log.info("=== Commit Summary ===");
        entityCounts.forEach((kind, count) -> log.info("  {}: {}", kind, count));
        log.info("  Total: {} created, {} updated, {} files committed, {} already committed, {} errors",
                created, updated, filesCommitted, filesAlreadyCommitted, errorMessages.size());

        return new CommitResult(filesCommitted, filesAlreadyCommitted, filesUnrecognized,
                created, updated, entityCounts, errorMessages.size(), errorMessages,
                finished.getId());
    }

    // ------------------------------------------------------------------
    // Per-kind record commits (natural-key upsert)
    // ------------------------------------------------------------------

    private FileOutcome commitRecords(String kind, ArrayNode records, UUID domainId,
                                      Resolver resolver, String fileName,
                                      List<String> errorMessages) {
        FileOutcome outcome = new FileOutcome();
        for (JsonNode record : records) {
            try {
                switch (kind) {
                    case "systems" -> commitSystem(record, domainId, outcome);
                    case "organization-members" -> commitMember(record, domainId, outcome);
                    case "processes" -> commitProcess(record, domainId, fileName, outcome);
                    case "canonical-entities" -> commitCanonicalEntity(record, domainId, resolver, outcome);
                    case "findings" -> commitFinding(record, domainId, resolver, outcome);
                    case "process-system-links" -> commitProcessSystemLink(record, resolver, outcome);
                    case "process-roles" -> commitProcessRole(record, resolver, outcome);
                    default -> throw new IllegalArgumentException("No handler for kind: " + kind);
                }
            } catch (RecordSkippedException e) {
                errorMessages.add("Skipped " + kind + " record in " + fileName + ": " + e.getMessage());
            }
        }
        return outcome;
    }

    private void commitSystem(JsonNode record, UUID domainId, FileOutcome outcome) {
        String name = requireText(record, "name");
        SystemEntity entity = systemRepository.findFirstByDomainIdAndName(domainId, name)
                .orElse(null);
        boolean isNew = entity == null;
        if (isNew) {
            entity = new SystemEntity();
            entity.setDomainId(domainId);
            entity.setName(name);
            entity.setCreatedBy("ingestion");
        }
        entity.setVendor(text(record, "vendor"));
        entity.setDescription(text(record, "description"));
        entity.setSystemType(text(record, "systemType"));
        entity.setDeployedYear(intVal(record, "deployedYear"));
        entity.setStatus(text(record, "status"));
        entity.setBusinessLine(text(record, "businessLine"));
        outcome.record(systemRepository.save(entity).getId(), isNew);
    }

    private void commitMember(JsonNode record, UUID domainId, FileOutcome outcome) {
        String name = requireText(record, "name");
        OrganizationMember member = organizationMemberRepository
                .findFirstByDomainIdAndName(domainId, name).orElse(null);
        boolean isNew = member == null;
        if (isNew) {
            member = new OrganizationMember();
            member.setDomainId(domainId);
            member.setName(name);
            member.setCreatedBy("ingestion");
        }
        member.setTitle(text(record, "title"));
        member.setDepartment(text(record, "department"));
        member.setBusinessLine(text(record, "businessLine"));
        member.setReportsTo(text(record, "reportsTo"));
        outcome.record(organizationMemberRepository.save(member).getId(), isNew);
    }

    private void commitProcess(JsonNode record, UUID domainId, String fileName, FileOutcome outcome) {
        String name = requireText(record, "name");
        Process process = processRepository.findFirstByDomainIdAndName(domainId, name).orElse(null);
        boolean isNew = process == null;
        if (isNew) {
            process = new Process();
            process.setDomainId(domainId);
            process.setName(name);
            process.setCreatedBy("ingestion");
        }
        // description is NOT NULL on processes — fall back loudly-visible, never crash the row.
        String description = text(record, "description");
        if (description == null) {
            description = text(record, "notes");
        }
        process.setDescription(description != null
                ? description
                : "Imported by ingestion from " + fileName + " (no description in source)");
        // Lenient vocabulary mapping: homepoc's prompts emitted values outside the canonical
        // enums (WORKAROUND, SHADOW_PROCESS, DATA_ENTRY) — map aliases, default OTHER.
        process.setClassification(parseEnum(ManualProcessClassification.class,
                text(record, "classification"),
                Map.of("WORKAROUND", "OTHER",
                        "DATA_ENTRY", "OTHER",
                        "SHADOW_PROCESS", "SHADOW_WORKFLOW",
                        "SIGNOFF", "SIGN_OFF"),
                ManualProcessClassification.OTHER));
        process.setFrequency(text(record, "frequency"));
        process.setPrimaryOwner(text(record, "primaryOwner"));
        process.setBusinessLine(text(record, "businessLine"));
        process.setDefenseLine(parseEnum(DefenseLine.class, text(record, "defenseLine"), Map.of(), null));
        process.setDocumentationQuality(parseEnum(DocumentationQuality.class,
                text(record, "documentationQuality"), Map.of(), null));
        process.setEstimatedFte(doubleVal(record, "estimatedFte"));
        process.setRiskRating(parseEnum(RiskRating.class, text(record, "riskRating"), Map.of(), null));
        String notes = text(record, "notes");
        if (notes != null) {
            ObjectNode metadata = objectMapper.createObjectNode();
            metadata.put("notes", notes);
            process.setMetadata(metadata);
        }
        outcome.record(processRepository.save(process).getId(), isNew);
    }

    private void commitCanonicalEntity(JsonNode record, UUID domainId, Resolver resolver,
                                       FileOutcome outcome) {
        String name = requireText(record, "name");
        CanonicalEntity entity = canonicalEntityRepository
                .findFirstByDomainIdAndName(domainId, name).orElse(null);
        boolean isNew = entity == null;
        if (isNew) {
            entity = new CanonicalEntity();
            entity.setDomainId(domainId);
            entity.setName(name);
            entity.setCreatedBy("ingestion");
        }
        String entityType = text(record, "entityType");
        entity.setEntityType(entityType != null ? entityType : "DATA_OBJECT");
        entity.setDescription(text(record, "description"));
        entity.setExecutionMode(parseEnum(ExecutionMode.class, text(record, "executionMode"),
                Map.of("AUTOMATED", "SYSTEM", "SEMI_AUTOMATED", "HYBRID"), null));
        String systemName = text(record, "systemName");
        if (systemName != null) {
            // Soft resolution: a canonical entity without a known parent system still commits.
            entity.setSystemId(resolver.resolveSystem(systemName, false));
        }
        outcome.record(canonicalEntityRepository.save(entity).getId(), isNew);
    }

    private void commitFinding(JsonNode record, UUID domainId, Resolver resolver,
                               FileOutcome outcome) {
        String title = requireText(record, "title");
        Finding finding = findingRepository.findFirstByDomainIdAndTitle(domainId, title).orElse(null);
        boolean isNew = finding == null;
        if (isNew) {
            finding = new Finding();
            finding.setDomainId(domainId);
            finding.setTitle(title);
            finding.setCreatedBy("ingestion");
        }
        FindingType findingType = parseEnum(FindingType.class, text(record, "findingType"),
                Map.of(), null);
        if (findingType == null) {
            throw new RecordSkippedException("unknown findingType '" + text(record, "findingType")
                    + "' for finding '" + title + "'");
        }
        finding.setFindingType(findingType);
        finding.setSeverity(parseEnum(Severity.class, text(record, "severity"), Map.of(), null));
        finding.setStatus(FindingStatus.OPEN);
        String description = text(record, "description");
        finding.setDescription(description != null ? description : title);
        finding.setRecommendation(text(record, "recommendation"));
        finding.setAiGenerated(false);
        finding.setAffectedSystemIds(resolveNameArray(record, "affectedSystemNames",
                n -> resolver.resolveSystem(n, false)));
        finding.setAffectedProcessIds(resolveNameArray(record, "affectedProcessNames",
                n -> resolver.resolveProcess(n, false)));
        String evidence = text(record, "evidence");
        if (evidence != null) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("text", evidence);
            finding.setEvidence(node);
        }
        outcome.record(findingRepository.save(finding).getId(), isNew);
    }

    private void commitProcessSystemLink(JsonNode record, Resolver resolver, FileOutcome outcome) {
        String processName = requireText(record, "processName");
        String systemName = requireText(record, "systemName");
        LinkType linkType = parseEnum(LinkType.class, text(record, "linkType"), Map.of(), null);
        if (linkType == null) {
            throw new RecordSkippedException("unknown linkType '" + text(record, "linkType")
                    + "' for link " + processName + " → " + systemName);
        }
        UUID processId = resolver.resolveProcess(processName, true);
        UUID systemId = resolver.resolveSystem(systemName, true);

        ProcessSystemLink link = processSystemLinkRepository
                .findFirstByProcessIdAndSystemIdAndLinkType(processId, systemId, linkType)
                .orElse(null);
        boolean isNew = link == null;
        if (isNew) {
            link = new ProcessSystemLink();
            link.setProcessId(processId);
            link.setSystemId(systemId);
            link.setLinkType(linkType);
            link.setCreatedBy("ingestion");
        }
        link.setFrequency(text(record, "frequency"));
        link.setStatus(LinkStatus.ACTIVE);
        link.setAiInferred(false);
        outcome.record(processSystemLinkRepository.save(link).getId(), isNew);
    }

    private void commitProcessRole(JsonNode record, Resolver resolver, FileOutcome outcome) {
        String processName = requireText(record, "processName");
        String memberName = requireText(record, "memberName");
        Participation participation = parseEnum(Participation.class,
                text(record, "participation"), Map.of(), null);
        if (participation == null) {
            throw new RecordSkippedException("unknown participation '" + text(record, "participation")
                    + "' for role " + memberName + " on " + processName);
        }
        UUID processId = resolver.resolveProcess(processName, true);
        UUID memberId = resolver.resolveOrCreateMember(memberName);

        ProcessRole role = processRoleRepository
                .findFirstByProcessIdAndMemberIdAndParticipation(processId, memberId, participation)
                .orElse(null);
        boolean isNew = role == null;
        if (isNew) {
            role = new ProcessRole();
            role.setProcessId(processId);
            role.setMemberId(memberId);
            role.setParticipation(participation);
            role.setCreatedBy("ingestion");
            outcome.record(processRoleRepository.save(role).getId(), true);
        }
        // Existing roles carry no updatable payload from staged records — nothing to overwrite.
    }

    // ------------------------------------------------------------------
    // name → UUID resolution
    // ------------------------------------------------------------------

    /**
     * Domain-scoped name→UUID resolver with per-run caching. Missing members
     * may be created as skeleton rows ({@code substrate.ingestion.create-missing-members});
     * missing systems/processes raise {@link RecordSkippedException} when
     * required, or resolve to null (with a recorded message) when soft.
     */
    private class Resolver {
        private final UUID domainId;
        private final List<String> errorMessages;
        private final Map<String, UUID> systemCache = new HashMap<>();
        private final Map<String, UUID> processCache = new HashMap<>();
        private final Map<String, UUID> memberCache = new HashMap<>();
        private final List<UUID> createdMemberIds = new ArrayList<>();

        Resolver(UUID domainId, List<String> errorMessages) {
            this.domainId = domainId;
            this.errorMessages = errorMessages;
        }

        UUID resolveSystem(String name, boolean required) {
            UUID id = systemCache.computeIfAbsent(name, n ->
                    systemRepository.findFirstByDomainIdAndName(domainId, n)
                            .map(SystemEntity::getId).orElse(null));
            if (id == null && required) {
                throw new RecordSkippedException("unresolved system name '" + name + "'");
            }
            if (id == null) {
                errorMessages.add("Unresolved system name (left null): '" + name + "'");
            }
            return id;
        }

        UUID resolveProcess(String name, boolean required) {
            UUID id = processCache.computeIfAbsent(name, n ->
                    processRepository.findFirstByDomainIdAndName(domainId, n)
                            .map(Process::getId).orElse(null));
            if (id == null && required) {
                throw new RecordSkippedException("unresolved process name '" + name + "'");
            }
            if (id == null) {
                errorMessages.add("Unresolved process name (left null): '" + name + "'");
            }
            return id;
        }

        UUID resolveOrCreateMember(String name) {
            UUID cached = memberCache.get(name);
            if (cached != null) {
                return cached;
            }
            UUID id = organizationMemberRepository.findFirstByDomainIdAndName(domainId, name)
                    .map(OrganizationMember::getId).orElse(null);
            if (id == null) {
                if (!properties.isCreateMissingMembers()) {
                    throw new RecordSkippedException("unresolved member name '" + name
                            + "' (create-missing-members=false)");
                }
                OrganizationMember member = new OrganizationMember();
                member.setDomainId(domainId);
                member.setName(name);
                member.setCreatedBy("ingestion");
                id = organizationMemberRepository.save(member).getId();
                createdMemberIds.add(id);
                log.info("Created skeleton organization member '{}' referenced by a process role", name);
            }
            memberCache.put(name, id);
            return id;
        }

        List<UUID> createdMemberIds() {
            return createdMemberIds;
        }
    }

    private ArrayNode resolveNameArray(JsonNode record, String field,
                                       java.util.function.Function<String, UUID> resolve) {
        ArrayNode resolved = objectMapper.createArrayNode();
        JsonNode names = record.get(field);
        if (names != null && names.isArray()) {
            for (JsonNode n : names) {
                UUID id = resolve.apply(n.asText());
                if (id != null) {
                    resolved.add(id.toString());
                }
            }
        }
        return resolved;
    }

    // ------------------------------------------------------------------
    // Registry / evidence / domain plumbing
    // ------------------------------------------------------------------

    private Domain resolveDomain(String domainName) {
        return domainRepository.findFirstByName(domainName).orElseGet(() -> {
            Domain domain = new Domain();
            domain.setName(domainName);
            domain.setDescription("Created by the ingestion commit stage");
            domain.setDemo(false);
            domain.setCreatedBy("ingestion");
            log.info("Creating target domain '{}'", domainName);
            return domainRepository.save(domain);
        });
    }

    private DocumentRegistry registerStagedDocument(Path file, String fileName, String sha256) {
        DocumentRegistry doc = new DocumentRegistry();
        doc.setSourceFile(fileName);
        doc.setSourceFileSha256(sha256);
        try {
            doc.setSourceFileSize(Files.size(file));
        } catch (IOException e) {
            doc.setSourceFileSize(null);
        }
        doc.setDocumentType(DocumentType.SAMPLE_DATA);
        doc.setSourceSystem("ingestion-staging");
        doc.setIngestedToTachyon(false);
        doc.setIngestionStatus(IngestionStatus.LOCAL_ONLY);
        doc.setCreatedBy("ingestion");
        return documentRegistryRepository.save(doc);
    }

    private void writeEvidence(List<UUID> createdIds, String kind, String sourceFile,
                               UUID documentRegistryId, UUID domainId) {
        if (createdIds.isEmpty()) {
            return;
        }
        String entityType = kind.endsWith("s") ? kind.substring(0, kind.length() - 1) : kind;
        List<Evidence> records = new ArrayList<>();
        for (UUID id : createdIds) {
            Evidence ev = new Evidence();
            ev.setEntityId(id);
            ev.setEntityType(entityType);
            ev.setDomainId(domainId);
            ev.setDocumentRegistryId(documentRegistryId);
            ev.setSourceFile(sourceFile);
            ev.setConfidence(1.0f);
            ev.setLabel("Committed from " + sourceFile);
            ev.setExtractor("ingestion");
            ev.setCreatedBy("ingestion");
            records.add(ev);
        }
        evidenceRepository.saveAll(records);
    }

    /** Stamp the ORIGINAL source files' registry rows (provenance loop, port-map §2.4 (a)/(d)). */
    private void stampManifestSources(Map<String, ConversionManifest.Entry> manifestByFile, UUID runId) {
        manifestByFile.values().stream()
                .map(ConversionManifest.Entry::documentRegistryId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .forEach(docId -> documentRegistryRepository.findById(docId).ifPresent(doc -> {
                    if (doc.getProcessedByConverterRunId() == null) {
                        doc.setProcessedByConverterRunId(runId);
                        documentRegistryRepository.save(doc);
                    }
                }));
    }

    private List<Path> listStagedFiles(Path stagingDir, List<String> errorMessages) {
        if (!Files.isDirectory(stagingDir)) {
            return List.of();
        }
        try (var stream = Files.list(stagingDir)) {
            return stream
                    .filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().equals(ConversionManifest.FILE_NAME))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            errorMessages.add("Failed to list staging directory " + stagingDir + ": " + e.getMessage());
            return List.of();
        }
    }

    private ConversionManifest readManifest(Path stagingDir) {
        Path manifestPath = stagingDir.resolve(ConversionManifest.FILE_NAME);
        if (!Files.isRegularFile(manifestPath)) {
            return null;
        }
        try {
            return objectMapper.readValue(Files.readAllBytes(manifestPath), ConversionManifest.class);
        } catch (IOException e) {
            log.warn("Unreadable conversion manifest {} — continuing without provenance: {}",
                    manifestPath, e.getMessage());
            return null;
        }
    }

    /**
     * Resolve the entity kind of a staged file: manifest entry → exact name →
     * dotted {@code .<kind>.json} suffix → longest-first substring fallback
     * (fixes homepoc's resolveEntityType ordering bug).
     */
    static String resolveEntityKind(String fileName, ConversionManifest.Entry manifestEntry) {
        if (manifestEntry != null && manifestEntry.entityKind() != null) {
            return manifestEntry.entityKind();
        }
        String exact = EXACT_FILE_KINDS.get(fileName.toLowerCase(Locale.ROOT));
        if (exact != null) {
            return exact;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String kind : KIND_ORDER) {
            if (lower.endsWith("." + kind + ".json")) {
                return kind;
            }
        }
        // Substring fallback for hand-named files — LONGEST patterns first so
        // "process-system-links" is never misrouted to "systems"/"processes".
        if (lower.contains("process-system-link")) return "process-system-links";
        if (lower.contains("process-role")) return "process-roles";
        if (lower.contains("organization-member")) return "organization-members";
        if (lower.contains("canonical-entit")) return "canonical-entities";
        if (lower.contains("finding")) return "findings";
        if (lower.contains("system")) return "systems";
        if (lower.contains("process") || lower.contains("workflow")) return "processes";
        return null;
    }

    // ------------------------------------------------------------------
    // Field/enum helpers
    // ------------------------------------------------------------------

    private static String text(JsonNode record, String field) {
        JsonNode node = record.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    private static String requireText(JsonNode record, String field) {
        String value = text(record, field);
        if (value == null) {
            throw new RecordSkippedException("missing required field '" + field + "'");
        }
        return value;
    }

    private static Integer intVal(JsonNode record, String field) {
        String value = text(record, field);
        if (value == null) {
            return null;
        }
        try {
            return (int) Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double doubleVal(JsonNode record, String field) {
        String value = text(record, field);
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Lenient enum parse: normalized (upper-cased, separators → underscore),
     * alias-mapped, falling back to {@code defaultValue} on unknown input.
     */
    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw,
                                                   Map<String, String> aliases, E defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        normalized = aliases.getOrDefault(normalized, normalized);
        try {
            return Enum.valueOf(type, normalized);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown {} value '{}' — using {}", type.getSimpleName(), raw, defaultValue);
            return defaultValue;
        }
    }

    private static String computeSha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** A record-level skip — collected as an error message, never a silent drop. */
    private static class RecordSkippedException extends RuntimeException {
        RecordSkippedException(String message) {
            super(message);
        }
    }

    private record StagedFile(Path path, String kind) {}

    /** Per-file created/updated bookkeeping. */
    private static class FileOutcome {
        private final List<UUID> createdIds = new ArrayList<>();
        private int updated;

        void record(UUID id, boolean isNew) {
            if (isNew) {
                createdIds.add(id);
            } else {
                updated++;
            }
        }

        List<UUID> createdIds() {
            return createdIds;
        }

        int created() {
            return createdIds.size();
        }

        int updated() {
            return updated;
        }
    }
}
