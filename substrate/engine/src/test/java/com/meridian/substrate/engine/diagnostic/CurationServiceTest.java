package com.meridian.substrate.engine.diagnostic;

import com.meridian.substrate.common.domain.AiCurationEvent;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.domain.Finding;
import com.meridian.substrate.common.domain.enums.CurationAction;
import com.meridian.substrate.common.domain.enums.CurationStatus;
import com.meridian.substrate.common.domain.enums.CurationSubjectKind;
import com.meridian.substrate.common.domain.enums.FindingStatus;
import com.meridian.substrate.common.domain.enums.FindingType;
import com.meridian.substrate.common.domain.enums.Severity;
import com.meridian.substrate.common.repo.AiCurationEventRepository;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.common.repo.FindingRepository;
import com.meridian.substrate.engine.EngineTestApplication;
import com.meridian.substrate.engine.diagnostic.exception.DiagnosticConflictException;
import com.meridian.substrate.engine.diagnostic.exception.RationaleRequiredException;
import com.meridian.substrate.engine.diagnostic.read.CurationDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Diagnostic test plan #7, service half (engine-spec diagnostic.md §9; the
 * REST/list-filter half is the app module's {@code FindingCurationApiTest}):
 *
 * <ul>
 *   <li>REJECT without rationale → 400-shaped {@code RationaleRequiredException},
 *       NO event appended;</li>
 *   <li>idempotency replay → original result, exactly one event ever;</li>
 *   <li>ACCEPT-after-REJECT projects ACCEPTED with full history preserved
 *       (append-only — rows never mutated or deleted);</li>
 *   <li>state guards: NULL-status fixture rows are not subject to curation;
 *       SUPERSEDED is terminal for the verbs; a spent key cannot replay as a
 *       different action.</li>
 * </ul>
 *
 * <p>H2 fast lane — Testcontainers-Postgres remains the acceptance gate (the
 * PG append-only trigger is CI-verified only).
 */
@SpringBootTest(classes = EngineTestApplication.class)
@ActiveProfiles("test")
@Transactional
class CurationServiceTest {

    @Autowired CurationService curationService;
    @Autowired DomainRepository domainRepository;
    @Autowired FindingRepository findingRepository;
    @Autowired AiCurationEventRepository eventRepository;

    private UUID domainId;

    @BeforeEach
    void fixture() {
        Domain domain = new Domain();
        domain.setName("curation " + UUID.randomUUID());
        domain.setCreatedBy("test");
        domainId = domainRepository.save(domain).getId();
    }

    // ------------------------------------------------------------- accept

    @Test
    void acceptFromPendingReview_projectsAccepted_andAppendsAttributedEvent() {
        Finding finding = finding(CurationStatus.PENDING_REVIEW);

        CurationDecision decision = curationService.accept(
                finding.getId(), "semantic support verified", "k-1", "Avery Operator", "p-9");

        assertThat(decision.curationStatus()).isEqualTo(CurationStatus.ACCEPTED);
        assertThat(decision.replayed()).isFalse();
        assertThat(decision.curatedByLabel()).isEqualTo("Avery Operator");

        Finding reloaded = findingRepository.findById(finding.getId()).orElseThrow();
        assertThat(reloaded.getCurationStatus()).isEqualTo(CurationStatus.ACCEPTED);
        assertThat(reloaded.getCuratedAt()).isNotNull();
        assertThat(reloaded.getCuratedByLabel()).isEqualTo("Avery Operator");

        List<AiCurationEvent> events = eventsOf(finding.getId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getAction()).isEqualTo(CurationAction.ACCEPT);
        assertThat(events.get(0).getActorLabel()).isEqualTo("Avery Operator");
        assertThat(events.get(0).getActorPersonaId()).isEqualTo("p-9");
        assertThat(events.get(0).getRationale()).isEqualTo("semantic support verified");
        assertThat(events.get(0).getDomainId()).isEqualTo(domainId);
    }

    // ------------------------------------------------------------- reject

    @Test
    void rejectWithoutRationale_isRationaleRequired_andAppendsNothing() {
        Finding finding = finding(CurationStatus.PENDING_REVIEW);

        assertThatThrownBy(() -> curationService.reject(
                finding.getId(), "  ", "k-1", "Avery Operator", null))
                .isInstanceOf(RationaleRequiredException.class);

        assertThat(eventsOf(finding.getId())).isEmpty();
        assertThat(findingRepository.findById(finding.getId()).orElseThrow().getCurationStatus())
                .isEqualTo(CurationStatus.PENDING_REVIEW);
    }

    @Test
    void reject_projectsRejected_rowStays_neverDeleted() {
        Finding finding = finding(CurationStatus.PENDING_REVIEW);

        CurationDecision decision = curationService.reject(
                finding.getId(), "not supported by the cited evidence", "k-1", "Avery", null);

        assertThat(decision.curationStatus()).isEqualTo(CurationStatus.REJECTED);
        Finding reloaded = findingRepository.findById(finding.getId()).orElseThrow();
        assertThat(reloaded.getCurationStatus()).isEqualTo(CurationStatus.REJECTED);
        assertThat(eventsOf(finding.getId())).hasSize(1);
        // rejection is data — the row stays (default LIST queries exclude it;
        // the app-module test pins that surface)
        assertThat(findingRepository.findById(finding.getId())).isPresent();
    }

    // -------------------------------------------- recourse + idempotency

    @Test
    void acceptAfterReject_projectsAccepted_withFullHistoryPreserved() {
        Finding finding = finding(CurationStatus.PENDING_REVIEW);
        curationService.reject(finding.getId(), "mis-click", "k-reject", "Avery", null);

        CurationDecision decision = curationService.accept(
                finding.getId(), null, "k-accept", "Blair Reviewer", null);

        assertThat(decision.curationStatus()).isEqualTo(CurationStatus.ACCEPTED);
        Finding reloaded = findingRepository.findById(finding.getId()).orElseThrow();
        assertThat(reloaded.getCurationStatus()).isEqualTo(CurationStatus.ACCEPTED);
        assertThat(reloaded.getCuratedByLabel()).isEqualTo("Blair Reviewer");

        List<AiCurationEvent> events = eventsOf(finding.getId());
        assertThat(events).hasSize(2); // history never deleted — append-only recourse
        assertThat(events).extracting(AiCurationEvent::getAction)
                .containsExactlyInAnyOrder(CurationAction.REJECT, CurationAction.ACCEPT);
    }

    @Test
    void idempotencyReplay_returnsOriginalResult_noNewEvent() {
        Finding finding = finding(CurationStatus.PENDING_REVIEW);
        CurationDecision original = curationService.accept(
                finding.getId(), null, "k-same", "Avery", null);

        CurationDecision replay = curationService.accept(
                finding.getId(), null, "k-same", "Someone Else", null);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.eventId()).isEqualTo(original.eventId());
        assertThat(replay.curationStatus()).isEqualTo(CurationStatus.ACCEPTED);
        assertThat(replay.curatedByLabel()).isEqualTo("Avery"); // the ORIGINAL actor
        assertThat(eventsOf(finding.getId())).hasSize(1);
    }

    @Test
    void spentKeyReplayedAsDifferentAction_isCurationConflict() {
        Finding finding = finding(CurationStatus.PENDING_REVIEW);
        curationService.accept(finding.getId(), null, "k-same", "Avery", null);

        assertThatThrownBy(() -> curationService.reject(
                finding.getId(), "now I changed my mind", "k-same", "Avery", null))
                .isInstanceOf(DiagnosticConflictException.class)
                .satisfies(ex -> assertThat(((DiagnosticConflictException) ex).getCode())
                        .isEqualTo(DiagnosticConflictException.Code.CURATION_CONFLICT));
        assertThat(eventsOf(finding.getId())).hasSize(1);
    }

    // -------------------------------------------------------- state guards

    @Test
    void fixtureRowWithNullStatus_isNotSubjectToCuration() {
        Finding fixture = finding(null); // operator/fixture row — NULL projection

        assertThatThrownBy(() -> curationService.accept(
                fixture.getId(), null, "k-1", "Avery", null))
                .isInstanceOf(DiagnosticConflictException.class)
                .satisfies(ex -> assertThat(((DiagnosticConflictException) ex).getCode())
                        .isEqualTo(DiagnosticConflictException.Code.CURATION_CONFLICT));
        assertThat(eventsOf(fixture.getId())).isEmpty();
    }

    @Test
    void supersededIsTerminalForTheOperatorVerbs() {
        Finding superseded = finding(CurationStatus.SUPERSEDED);

        assertThatThrownBy(() -> curationService.accept(
                superseded.getId(), null, "k-1", "Avery", null))
                .isInstanceOf(DiagnosticConflictException.class);
        assertThatThrownBy(() -> curationService.reject(
                superseded.getId(), "why", "k-2", "Avery", null))
                .isInstanceOf(DiagnosticConflictException.class);
    }

    @Test
    void reRejectWithFreshKey_isAStateConflict_notASilentSecondEvent() {
        Finding finding = finding(CurationStatus.PENDING_REVIEW);
        curationService.reject(finding.getId(), "first rejection", "k-1", "Avery", null);

        assertThatThrownBy(() -> curationService.reject(
                finding.getId(), "second rejection", "k-2", "Avery", null))
                .isInstanceOf(DiagnosticConflictException.class);
        assertThat(eventsOf(finding.getId())).hasSize(1);
    }

    // --------------------------------------------------------------- helpers

    private Finding finding(CurationStatus status) {
        Finding finding = new Finding();
        finding.setDomainId(domainId);
        finding.setFindingType(FindingType.DATA_QUALITY);
        finding.setSeverity(Severity.MEDIUM);
        finding.setStatus(FindingStatus.OPEN);
        finding.setTitle("Curation subject");
        finding.setDescription("A finding under curation test.");
        finding.setAiGenerated(status != null);
        finding.setCurationStatus(status);
        finding.setCreatedBy("test");
        return findingRepository.save(finding);
    }

    private List<AiCurationEvent> eventsOf(UUID findingId) {
        return eventRepository.findBySubjectKindAndSubjectIdOrderByCreatedAtAsc(
                CurationSubjectKind.FINDING, findingId);
    }
}
