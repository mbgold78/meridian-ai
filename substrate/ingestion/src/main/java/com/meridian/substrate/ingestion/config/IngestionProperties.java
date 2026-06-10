package com.meridian.substrate.ingestion.config;

import com.meridian.substrate.ingestion.readers.DocumentTag;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration properties for the ingestion module ({@code substrate.ingestion.*}).
 *
 * <p>Every key has a safe in-code default — missing configuration never breaks
 * boot (binding contract for chunked builds). The routing rules and the
 * tag→prompt-template map externalize homepoc's compile-time
 * {@code ConversionPipeline.PROMPT_MAPPINGS} table (port-map §2.4); the
 * defaults reproduce it faithfully, first-match-wins.
 */
@Component
@ConfigurationProperties(prefix = "substrate.ingestion")
public class IngestionProperties {

    /** Default input directory for the convert command. */
    private String samplesDir = "./samples";

    /** Default output directory for converted entity JSON. */
    private String outputDir = "./output";

    /** Staging directory (human-review gate before commit). */
    private String stagingDir = "./staging";

    /**
     * Name of the target Domain that committed entities attach to (name→UUID
     * resolution scope). Created on first commit if absent. Overridable per
     * invocation via the {@code --domain} CLI flag.
     */
    private String domainName = "Ingested";

    /**
     * When a process-role references a participant with no matching
     * OrganizationMember, create a skeleton member (true) or record a
     * resolution error (false).
     */
    private boolean createMissingMembers = true;

    /**
     * Maximum characters of extracted text sent to the LLM in one request;
     * larger inputs are chunked on row/paragraph boundaries and the extracted
     * record arrays merged (replaces homepoc's silent context overflow).
     */
    private int maxInputChars = 60_000;

    /** Cap on error messages persisted to the IngestRun audit row. */
    private int maxPersistedErrors = 100;

    private final Cli cli = new Cli();

    /** First-match-wins document routing rules (pattern + extension → tag). */
    private List<RoutingRule> routingRules = defaultRoutingRules();

    /** Tag → prompt template (classpath:prompts/) used for LLM extraction. */
    private Map<DocumentTag, String> promptTemplates = defaultPromptTemplates();

    public String getSamplesDir() { return samplesDir; }
    public void setSamplesDir(String samplesDir) { this.samplesDir = samplesDir; }

    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }

    public String getStagingDir() { return stagingDir; }
    public void setStagingDir(String stagingDir) { this.stagingDir = stagingDir; }

    public String getDomainName() { return domainName; }
    public void setDomainName(String domainName) { this.domainName = domainName; }

    public boolean isCreateMissingMembers() { return createMissingMembers; }
    public void setCreateMissingMembers(boolean createMissingMembers) { this.createMissingMembers = createMissingMembers; }

    public int getMaxInputChars() { return maxInputChars; }
    public void setMaxInputChars(int maxInputChars) { this.maxInputChars = maxInputChars; }

    public int getMaxPersistedErrors() { return maxPersistedErrors; }
    public void setMaxPersistedErrors(int maxPersistedErrors) { this.maxPersistedErrors = maxPersistedErrors; }

    public Cli getCli() { return cli; }

    public List<RoutingRule> getRoutingRules() { return routingRules; }
    public void setRoutingRules(List<RoutingRule> routingRules) { this.routingRules = routingRules; }

    public Map<DocumentTag, String> getPromptTemplates() { return promptTemplates; }
    public void setPromptTemplates(Map<DocumentTag, String> promptTemplates) { this.promptTemplates = promptTemplates; }

    public static class Cli {
        /** Disable to keep the CommandLineRunner inert (test contexts). */
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /** A single routing rule: file name contains {@code pattern} AND has {@code extension}. */
    public static class RoutingRule {
        private String pattern;
        private String extension;
        private DocumentTag tag;

        public RoutingRule() {
        }

        public RoutingRule(String pattern, String extension, DocumentTag tag) {
            this.pattern = pattern;
            this.extension = extension;
            this.tag = tag;
        }

        public String getPattern() { return pattern; }
        public void setPattern(String pattern) { this.pattern = pattern; }

        public String getExtension() { return extension; }
        public void setExtension(String extension) { this.extension = extension; }

        public DocumentTag getTag() { return tag; }
        public void setTag(DocumentTag tag) { this.tag = tag; }
    }

    /** Mirrors homepoc's PROMPT_MAPPINGS table (order matters — first match wins). */
    static List<RoutingRule> defaultRoutingRules() {
        List<RoutingRule> rules = new ArrayList<>();
        rules.add(new RoutingRule("system", "csv", DocumentTag.SYSTEM_CSV));
        rules.add(new RoutingRule("manual", "csv", DocumentTag.MANUAL_PROCESS_CSV));
        rules.add(new RoutingRule("process", "csv", DocumentTag.MANUAL_PROCESS_CSV));
        rules.add(new RoutingRule("entit", "csv", DocumentTag.ENTITY_CSV));
        rules.add(new RoutingRule("workflow", "txt", DocumentTag.WORKFLOW_TEXT));
        rules.add(new RoutingRule("workflow", "md", DocumentTag.WORKFLOW_TEXT));
        return rules;
    }

    static Map<DocumentTag, String> defaultPromptTemplates() {
        Map<DocumentTag, String> templates = new EnumMap<>(DocumentTag.class);
        templates.put(DocumentTag.SYSTEM_CSV, "convert-csv-systems.md");
        templates.put(DocumentTag.MANUAL_PROCESS_CSV, "convert-csv-manual-process.md");
        templates.put(DocumentTag.ENTITY_CSV, "convert-csv-entities.md");
        templates.put(DocumentTag.GENERIC_CSV, "convert-csv-entities.md");
        templates.put(DocumentTag.GENERIC_JSON, "convert-csv-entities.md");
        templates.put(DocumentTag.WORKFLOW_TEXT, "convert-text-workflow-description.md");
        templates.put(DocumentTag.GENERIC_TEXT, "convert-text-workflow-description.md");
        return templates;
    }
}
