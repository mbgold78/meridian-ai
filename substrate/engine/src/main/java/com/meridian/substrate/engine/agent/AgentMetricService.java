package com.meridian.substrate.engine.agent;

import com.meridian.substrate.common.domain.AgentMetricSample;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.enums.AgentSightingChannel;
import com.meridian.substrate.common.repo.AgentMetricSampleRepository;
import com.meridian.substrate.common.repo.AgentRegistrationRepository;
import com.meridian.substrate.engine.contract.gateway.Governed;
import com.meridian.substrate.engine.contract.gateway.GovernedActions;
import com.meridian.substrate.engine.contract.gateway.Ungoverned;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Push-based agent metric samples (engine-spec agents.md §9.2; ADR-003 D9;
 * ADR-002 D11/D13 idioms): idempotent upsert keyed
 * {@code (agent, metric_key, period_key)} + a METRIC_REPORT sighting
 * (liveness for free). {@code adherence} is just {@code metric_key='adherence'}
 * — severing the S6 fixture-stamped number; the engine computes nothing it
 * was not told (EXTENSION POINT(agent-6): no adherence rubric exists).
 *
 * <p>Period keys are CALLER-SUPPLIED (contracts §5.1 conventions); the
 * evaluator (6b/6c) computes most-recent-closed periods at read.
 *
 * <p>EXTENSION POINT(agent-12): agent-authored Assertions
 * ([EVIDENCE-GROUNDING] ProducedBy=AGENT) have NO write path in v1 — metric
 * samples are the only agent-reported numbers, and filter-at-interpretation
 * applies when assertions are ever built (agent architecture doc / Phase 6
 * backlog).
 */
@Service
public class AgentMetricService {

    /** §7.3: the nullable fleet-row {@code adherence} reads the latest sample of this key. */
    public static final String METRIC_ADHERENCE = "adherence";

    private final AgentRegistrationRepository registrationRepository;
    private final AgentMetricSampleRepository sampleRepository;
    private final AgentLivenessService livenessService;
    private final Clock clock;

    public AgentMetricService(AgentRegistrationRepository registrationRepository,
                              AgentMetricSampleRepository sampleRepository,
                              AgentLivenessService livenessService,
                              Clock clock) {
        this.registrationRepository = registrationRepository;
        this.sampleRepository = sampleRepository;
        this.livenessService = livenessService;
        this.clock = clock;
    }

    /** One §12 {@code report_agent_activity} metric entry. */
    public record ActivityMetric(String metricKey, double value, String periodKey) {
    }

    /**
     * §12 {@code report_agent_activity} engine half (6c): an optional
     * activity note lands as a METRIC_REPORT sighting DETAIL (the
     * {@code recentActivity} derivation reads details, §7.3) and each metric
     * entry rides the idempotent {@link #report} upsert. GOVERNED as
     * {@code agent.report_activity} — observation scope, so SHADOW and
     * read-only agents retain it (§8.3 #2/#3).
     */
    @Governed(actionType = GovernedActions.AGENT_REPORT_ACTIVITY)
    @Transactional
    public List<AgentMetricSample> reportActivity(UUID agentRegistrationId, String activityNote,
                                                  List<ActivityMetric> metrics,
                                                  String reportedByPrincipal) {
        if (activityNote != null && !activityNote.isBlank()) {
            String note = activityNote.trim();
            livenessService.recordSighting(agentRegistrationId,
                    AgentSightingChannel.METRIC_REPORT,
                    note.length() <= 500 ? note : note.substring(0, 500));
        }
        List<AgentMetricSample> samples = new ArrayList<>();
        for (ActivityMetric metric : metrics == null ? List.<ActivityMetric>of() : metrics) {
            samples.add(report(agentRegistrationId, metric.metricKey(), metric.value(),
                    metric.periodKey(), reportedByPrincipal));
        }
        if ((activityNote == null || activityNote.isBlank()) && samples.isEmpty()) {
            throw new IllegalArgumentException(
                    "report_agent_activity needs an activity_note and/or metrics entries (spec §12)");
        }
        return samples;
    }

    /**
     * Idempotent upsert (uq_ams_sample): re-reporting the same
     * {@code (metric, period)} overwrites the value + reportedAt — the D13
     * idiom; no duplicate rows, no error. Records a METRIC_REPORT sighting
     * with a {@code null} detail (the sample row holds the data; the
     * {@code recentActivity} derivation reads sighting DETAILS, §7.3).
     */
    @Ungoverned(reason = "push-based telemetry upsert, not a lifecycle verb or ADR D2 PEP — the"
            + " 6c MCP report tools are the governed lane for AGENT principals; this service is"
            + " their engine half and the DemoPack seed lane (engine-spec agents.md §9.2/§12)")
    @Transactional
    public AgentMetricSample report(UUID agentRegistrationId, String metricKey, double value,
                                    String periodKey, String reportedByPrincipal) {
        if (metricKey == null || metricKey.isBlank()) {
            throw new IllegalArgumentException("metricKey is required (spec §9.2)");
        }
        if (periodKey == null || periodKey.isBlank()) {
            throw new IllegalArgumentException("periodKey is caller-supplied and required"
                    + " (contracts §5.1 conventions; spec §1)");
        }
        AgentRegistration reg = registrationRepository.findByIdForUpdate(agentRegistrationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "agent registration not found: " + agentRegistrationId));

        Optional<AgentMetricSample> existing = sampleRepository
                .findByAgentRegistrationIdAndMetricKeyAndPeriodKey(reg.getId(), metricKey, periodKey);
        AgentMetricSample sample = existing.orElseGet(AgentMetricSample::new);
        sample.setAgentRegistrationId(reg.getId());
        sample.setMetricKey(metricKey);
        sample.setMetricValue(value);
        sample.setPeriodKey(periodKey);
        sample.setReportedByPrincipal(reportedByPrincipal);
        sample.setReportedAt(clock.instant());
        sample = sampleRepository.save(sample);

        livenessService.recordSighting(reg.getId(), AgentSightingChannel.METRIC_REPORT, null);
        return sample;
    }
}
