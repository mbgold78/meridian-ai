package com.meridian.substrate.ingestion.commit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the entity-kind resolution fix (port-map §2.4): homepoc's substring
 * ordering matched "system" inside "process-system-links.json" and misrouted
 * link records to the system repository.
 */
class CommitKindResolutionTest {

    @Test
    void dottedSuffixResolvesConverterOutputNames() {
        assertThat(CommitService.resolveEntityKind("glacier-systems.systems.json", null))
                .isEqualTo("systems");
        assertThat(CommitService.resolveEntityKind("glacier-manual-processes.processes.json", null))
                .isEqualTo("processes");
        assertThat(CommitService.resolveEntityKind(
                "glacier-manual-processes.process-system-links.json", null))
                .isEqualTo("process-system-links");
        assertThat(CommitService.resolveEntityKind(
                "glacier-manual-processes.process-roles.json", null))
                .isEqualTo("process-roles");
        assertThat(CommitService.resolveEntityKind("glacier-entities.canonical-entities.json", null))
                .isEqualTo("canonical-entities");
    }

    @Test
    void exactHandAuthoredNamesResolve() {
        assertThat(CommitService.resolveEntityKind("systems.json", null)).isEqualTo("systems");
        assertThat(CommitService.resolveEntityKind("process-system-links.json", null))
                .isEqualTo("process-system-links");
        assertThat(CommitService.resolveEntityKind("organization-members.json", null))
                .isEqualTo("organization-members");
    }

    @Test
    void substringFallbackChecksLongestPatternsFirst_fixesHomepocMisrouting() {
        // The homepoc bug: "system" matched before the link check.
        assertThat(CommitService.resolveEntityKind("my-process-system-link-export.json", null))
                .isEqualTo("process-system-links");
        assertThat(CommitService.resolveEntityKind("my-process-role-export.json", null))
                .isEqualTo("process-roles");
        assertThat(CommitService.resolveEntityKind("core-system-list.json", null))
                .isEqualTo("systems");
    }

    @Test
    void unknownNamesYieldNull() {
        assertThat(CommitService.resolveEntityKind("random-stuff.json", null)).isNull();
    }
}
