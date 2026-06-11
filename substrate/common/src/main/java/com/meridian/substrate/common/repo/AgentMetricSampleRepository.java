package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.AgentMetricSample;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link AgentMetricSample} rows (engine-spec agents.md §2/§4
 * table 7; ADR-002 D11/D13 idioms — idempotent upsert keyed
 * {@code (agent, metric_key, period_key)}).
 */
@Repository
public interface AgentMetricSampleRepository extends JpaRepository<AgentMetricSample, UUID> {

    /** Upsert lookup (uq_ams_sample). */
    Optional<AgentMetricSample> findByAgentRegistrationIdAndMetricKeyAndPeriodKey(
            UUID agentRegistrationId, String metricKey, String periodKey);

    List<AgentMetricSample> findByAgentRegistrationIdAndMetricKeyOrderByReportedAtDesc(
            UUID agentRegistrationId, String metricKey);

    List<AgentMetricSample> findByAgentRegistrationIdAndReportedAtAfterOrderByReportedAtDesc(
            UUID agentRegistrationId, Instant since);

    /** Latest sample of a key — the nullable {@code adherence} DTO source (spec §7.3). */
    Optional<AgentMetricSample> findFirstByAgentRegistrationIdAndMetricKeyOrderByReportedAtDesc(
            UUID agentRegistrationId, String metricKey);
}
